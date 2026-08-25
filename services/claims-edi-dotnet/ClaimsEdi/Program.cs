using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;
using System.Threading.Tasks;

namespace Firmus.Interop.ClaimsEdi;

/// <summary>
/// The claims tail of the interoperability pipeline.
/// </summary>
/// <remarks>
/// <para>
/// A polling batch worker rather than a streaming consumer, because that is what claims actually
/// are: charge capture closes, coding finishes, and a batch is submitted. Files land in an input
/// directory as canonical admission events joined with their charge detail; each becomes one 837P
/// interchange, is read back by this service's own parser, and is published to SQS.
/// </para>
/// <para>
/// Logging policy: control numbers, claim ids and error codes, never element values and never the
/// interchange itself. An 837P is PHI, and application logs are rarely under the same retention
/// and access controls as the claim store — the same gap SOC 2 CC6.1 and the HIPAA
/// minimum-necessary rule are about. Rejection detail, which can quote a patient's name back at
/// you, is written next to the quarantined input file instead, where it inherits that file's
/// access controls.
/// </para>
/// </remarks>
public static class Program
{
    public static async Task<int> Main(string[] args)
    {
        string command = args.Length > 0 ? args[0] : "serve";

        try
        {
            switch (command)
            {
                case "serve":
                    return await ServeAsync().ConfigureAwait(false);
                case "build":
                    return Build(args);
                case "verify":
                    return Verify(args);
                case "healthcheck":
                    return await HealthcheckAsync().ConfigureAwait(false);
                case "help":
                case "-h":
                case "--help":
                    return Help();
                default:
                    Console.Error.WriteLine($"unknown command '{command}'");
                    Help();
                    return 64; // EX_USAGE
            }
        }
        catch (X12Exception ex)
        {
            Console.Error.WriteLine(ex.Message);
            return 65; // EX_DATAERR
        }
        catch (ArgumentException ex)
        {
            Console.Error.WriteLine("configuration: " + ex.Message);
            return 78; // EX_CONFIG
        }
        catch (IOException ex)
        {
            Console.Error.WriteLine("io: " + ex.Message);
            return 74; // EX_IOERR
        }
    }

    private static int Help()
    {
        Console.Out.WriteLine(
            """
            claims-edi — canonical admission events to X12 837P

              serve                 poll EDI_INPUT_DIR, build 837P, publish to SQS (default)
              build [file|-]        build one interchange and write it to stdout
              verify [file|-]       parse an interchange and validate its envelope
              healthcheck           probe the local health endpoint; exit 0 when healthy
              help                  this text

            Configuration is environment-only; see README.md for the table.
            """);
        return 0;
    }

    private static async Task<int> ServeAsync()
    {
        ServiceConfig config = ServiceConfig.FromEnvironment();

        Directory.CreateDirectory(config.InputDirectory);
        Directory.CreateDirectory(config.OutputDirectory);
        Directory.CreateDirectory(config.FailedDirectory);

        using var shutdown = new CancellationTokenSource();

        Console.CancelKeyPress += (_, eventArgs) =>
        {
            eventArgs.Cancel = true;
            shutdown.Cancel();
        };

        // SIGTERM is what an orchestrator sends before SIGKILL. Without this the process dies at
        // the end of the grace period mid-batch, and a claim that was written to the outbox but
        // whose input file had not yet been deleted is rebuilt — with a new ISA13 — on restart.
        using PosixSignalRegistration sigterm = PosixSignalRegistration.Create(
            PosixSignal.SIGTERM,
            context =>
            {
                context.Cancel = true;
                shutdown.Cancel();
            });

        using var health = new HealthEndpoint(config.HealthPort);
        health.Start();

        var sequence = new FileControlNumberSequence(config.ControlNumberFile);
        var builder = new Claim837PBuilder(config.Profile);
        var writer = new X12Writer(config.Delimiters, config.DelimiterPolicy);
        var reader = new X12Reader();

        string? queueUrl = config.QueueUrl;
        ISqsPublisher publisher;
        if (queueUrl is null)
        {
            // The dry-run publisher is given a no-op writer here: the interchange still lands in
            // the outbox, but it does not go to stdout, because stdout is the log.
            publisher = new DryRunPublisher(_ => { });
        }
        else
        {
            publisher = SqsPublisher.ForQueue(queueUrl, config.AwsRegion);
        }

        Log($"started mode={(queueUrl is null ? "dry-run" : "sqs")} inbox={config.InputDirectory} health=:{config.HealthPort.ToString(CultureInfo.InvariantCulture)} usage={config.Profile.UsageIndicator}");

        try
        {
            while (!shutdown.IsCancellationRequested)
            {
                int processed = await ProcessInboxAsync(
                    config, builder, writer, reader, sequence, publisher, shutdown.Token)
                    .ConfigureAwait(false);

                if (processed == 0)
                {
                    await Task.Delay(config.PollInterval, shutdown.Token).ConfigureAwait(false);
                }
            }
        }
        catch (OperationCanceledException)
        {
            Log("stopping");
        }
        catch (Exception ex)
        {
            // Deliberately broad. The loop is the process: anything that escapes it means this
            // instance can no longer make progress. Marking the endpoint degraded covers the
            // window before the process is gone; exiting non-zero is what actually gets us
            // replaced. Spinning and re-logging the same error would keep the pod "up" while it
            // silently stops submitting claims, which is the worse outcome.
            health.MarkUnhealthy();
            Console.Error.WriteLine("fatal: " + ex.GetType().Name + ": " + ex.Message);
            return 70; // EX_SOFTWARE
        }
        finally
        {
            (publisher as IDisposable)?.Dispose();
        }

        return 0;
    }

    private static async Task<int> ProcessInboxAsync(
        ServiceConfig config,
        Claim837PBuilder builder,
        X12Writer writer,
        X12Reader reader,
        IControlNumberSequence sequence,
        ISqsPublisher publisher,
        CancellationToken cancellationToken)
    {
        var files = new List<string>(Directory.EnumerateFiles(config.InputDirectory, "*.json"));

        // Ordinal sort so a batch written as 0001.json..0100.json is submitted in the order the
        // sender numbered it. Directory enumeration order is not defined.
        files.Sort(StringComparer.Ordinal);

        int processed = 0;

        foreach (string file in files)
        {
            cancellationToken.ThrowIfCancellationRequested();
            string name = Path.GetFileNameWithoutExtension(file);

            try
            {
                string json = File.ReadAllText(file);
                ClaimRequestDocument document = CanonicalJson.ParseClaimRequest(json);
                ClaimRequest claim = ClaimRequest.From(document);

                X12ControlNumbers controls = X12ControlNumbers.From(sequence.Next());
                X12Interchange interchange = builder.Build(
                    claim, controls, DateTimeOffset.UtcNow, config.Delimiters);

                string edi = writer.Write(interchange);

                // Read our own output back before it leaves the process. It costs microseconds and
                // it means a builder change that desynchronises the envelope fails here, on our
                // side, instead of arriving as a 999 rejection days later.
                _ = reader.Read(edi);

                var submission = new ClaimSubmission(
                    claim.ClaimId, claim.GroupKey, controls.Interchange, edi);

                ClaimPublishReceipt receipt = await publisher
                    .PublishAsync(submission, cancellationToken)
                    .ConfigureAwait(false);

                File.WriteAllText(Path.Combine(config.OutputDirectory, name + ".edi"), edi);

                // Delete last. If the process dies between publish and delete the claim is rebuilt
                // on restart — with a new ISA13 but the same claim id, which is exactly the case
                // the SQS deduplication id is derived from the claim id to absorb.
                File.Delete(file);

                Log($"published file={name} claim={claim.ClaimId} isa13={controls.Interchange} gs06={controls.Group} messageId={receipt.MessageId}");
                processed++;
            }
            catch (X12Exception ex)
            {
                Quarantine(config, file, name, ex);
                Console.Error.WriteLine(
                    $"rejected file={name} code={ex.Code} segment={ex.SegmentPosition.ToString(CultureInfo.InvariantCulture)} (detail beside the quarantined file)");
                processed++;
            }
            catch (IOException ex)
            {
                // Transient. Leave the file where it is and pick it up on the next pass rather than
                // quarantining a claim because a volume was briefly unavailable.
                Console.Error.WriteLine($"deferred file={name}: {ex.GetType().Name}");
            }
        }

        return processed;
    }

    /// <summary>
    /// Moves a rejected input out of the inbox and writes the reason beside it.
    /// </summary>
    /// <remarks>
    /// The reason goes next to the file rather than into the log because rejection messages quote
    /// the data that caused them — a patient's name containing a delimiter, a malformed birth date.
    /// Beside the file it inherits the input volume's access controls; in the log it does not.
    /// </remarks>
    private static void Quarantine(ServiceConfig config, string file, string name, X12Exception error)
    {
        try
        {
            string destination = Path.Combine(config.FailedDirectory, name + ".json");
            File.Move(file, destination, overwrite: true);
            File.WriteAllText(
                Path.Combine(config.FailedDirectory, name + ".error"),
                error.Message + Environment.NewLine);
        }
        catch (IOException ex)
        {
            Console.Error.WriteLine($"quarantine failed file={name}: {ex.GetType().Name}");
        }
    }

    private static int Build(string[] args)
    {
        ServiceConfig config = ServiceConfig.FromEnvironment();
        ClaimRequestDocument document = CanonicalJson.ParseClaimRequest(ReadInput(args));
        ClaimRequest claim = ClaimRequest.From(document);

        // A fixed control number: `build` is a development and diffing tool, and an output that
        // changes on every invocation is useless for that. `serve` uses the persisted sequence.
        X12ControlNumbers controls = X12ControlNumbers.From(1);

        X12Interchange interchange = new Claim837PBuilder(config.Profile)
            .Build(claim, controls, DateTimeOffset.UtcNow, config.Delimiters);

        string edi = new X12Writer(config.Delimiters, config.DelimiterPolicy).Write(interchange);

        Console.Out.WriteLine(edi);
        return 0;
    }

    private static int Verify(string[] args)
    {
        X12Interchange interchange = new X12Reader().Read(ReadInput(args));

        Console.Out.WriteLine(
            $"interchange isa13={interchange.Header.ControlNumber} sender={interchange.Header.SenderQualifier}:{interchange.Header.SenderId} receiver={interchange.Header.ReceiverQualifier}:{interchange.Header.ReceiverId} usage={interchange.Header.UsageIndicator}");
        Console.Out.WriteLine($"delimiters {interchange.Delimiters}");

        foreach (X12FunctionalGroup group in interchange.Groups)
        {
            Console.Out.WriteLine(
                $"  group gs01={group.Header.FunctionalIdentifierCode} gs06={group.Header.ControlNumber} version={group.Header.VersionReleaseCode} sets={group.TransactionSets.Count.ToString(CultureInfo.InvariantCulture)}");

            foreach (X12TransactionSet set in group.TransactionSets)
            {
                Console.Out.WriteLine(
                    $"    set st01={set.IdentifierCode} st02={set.ControlNumber} guide={set.ImplementationReference ?? "(none)"} segments={(set.Segments.Count + 2).ToString(CultureInfo.InvariantCulture)}");
            }
        }

        Console.Out.WriteLine("envelope ok: control numbers linked, segment counts agree");
        return 0;
    }

    /// <summary>
    /// Probes the local health endpoint.
    /// </summary>
    /// <remarks>
    /// Exists so the container image needs neither curl nor wget. The runtime image ships with
    /// neither, and installing one to answer a HEALTHCHECK adds a package and its CVE stream to
    /// every deployment. The cost is a short-lived runtime process every probe interval, which is
    /// the cheaper of the two.
    /// </remarks>
    private static async Task<int> HealthcheckAsync()
    {
        ServiceConfig config = ServiceConfig.FromEnvironment();

        try
        {
            using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(3));
            using var client = new TcpClient();

            await client.ConnectAsync(IPAddress.Loopback, config.HealthPort, timeout.Token)
                .ConfigureAwait(false);

            using NetworkStream stream = client.GetStream();

            byte[] request = Encoding.ASCII.GetBytes(
                "GET /healthz HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
            await stream.WriteAsync(request, timeout.Token).ConfigureAwait(false);

            var buffer = new byte[256];
            int read = await stream.ReadAsync(buffer, timeout.Token).ConfigureAwait(false);
            string statusLine = Encoding.ASCII.GetString(buffer, 0, read);

            return statusLine.StartsWith("HTTP/1.1 200", StringComparison.Ordinal) ? 0 : 1;
        }
        catch (SocketException)
        {
            return 1;
        }
        catch (IOException)
        {
            return 1;
        }
        catch (OperationCanceledException)
        {
            return 1;
        }
    }

    private static string ReadInput(string[] args)
    {
        string? path = args.Length > 1 ? args[1] : null;

        if (path is null || path == "-")
        {
            return Console.In.ReadToEnd();
        }

        return File.ReadAllText(path);
    }

    private static void Log(string message) =>
        Console.Out.WriteLine(
            DateTimeOffset.UtcNow.ToString("yyyy-MM-dd'T'HH:mm:ss'Z'", CultureInfo.InvariantCulture)
            + " claims-edi " + message);
}
