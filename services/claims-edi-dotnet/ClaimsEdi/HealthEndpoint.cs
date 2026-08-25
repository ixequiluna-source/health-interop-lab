using System;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Threading;
using System.Threading.Tasks;

namespace Firmus.Interop.ClaimsEdi;

/// <summary>
/// A minimal HTTP liveness endpoint.
/// </summary>
/// <remarks>
/// <para>
/// Hand-rolled over <see cref="TcpListener"/> rather than hosted on ASP.NET Core. This service is a
/// batch worker with no HTTP surface of its own; taking the whole web stack as a dependency to
/// answer one probe would triple the image size and the patch surface for a
/// forty-line responder. The response is deliberately the smallest thing that satisfies a Docker
/// or Kubernetes probe: a status line, a content length, and <c>Connection: close</c>.
/// </para>
/// <para>
/// It reports liveness, not readiness. It answers 200 while the process is running and its poll
/// loop has not faulted, and 503 once the loop has faulted. It says nothing about whether SQS is
/// reachable — a health check that fails when a downstream dependency is unavailable causes the
/// orchestrator to restart a process that was working perfectly, turning a partner outage into an
/// outage of our own.
/// </para>
/// </remarks>
public sealed class HealthEndpoint : IDisposable
{
    private readonly TcpListener _listener;
    private readonly CancellationTokenSource _shutdown = new();
    private int _healthy = 1;
    private Task? _acceptLoop;
    private bool _disposed;

    public HealthEndpoint(int port)
    {
        if (port < 1 || port > 65535)
        {
            throw new ArgumentOutOfRangeException(nameof(port), port, "Not a TCP port.");
        }

        Port = port;
        _listener = new TcpListener(IPAddress.Any, port);
    }

    /// <summary>The port the endpoint listens on.</summary>
    public int Port { get; }

    /// <summary>True while the endpoint answers 200.</summary>
    public bool IsHealthy => Volatile.Read(ref _healthy) == 1;

    /// <summary>Starts listening. Returns as soon as the socket is bound.</summary>
    public void Start()
    {
        _listener.Start();
        _acceptLoop = Task.Run(() => AcceptLoopAsync(_shutdown.Token));
    }

    /// <summary>Flips the endpoint to 503. Irreversible on purpose: it means the worker faulted.</summary>
    public void MarkUnhealthy() => Volatile.Write(ref _healthy, 0);

    private async Task AcceptLoopAsync(CancellationToken cancellationToken)
    {
        while (!cancellationToken.IsCancellationRequested)
        {
            TcpClient client;
            try
            {
                client = await _listener.AcceptTcpClientAsync(cancellationToken).ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                return;
            }
            catch (ObjectDisposedException)
            {
                return;
            }
            catch (SocketException)
            {
                return;
            }

            // Each probe is answered on its own; one slow client must not block the next probe,
            // and a probe that times out is indistinguishable from a dead process to the
            // orchestrator.
            _ = RespondAsync(client, IsHealthy);
        }
    }

    private static async Task RespondAsync(TcpClient client, bool healthy)
    {
        using (client)
        {
            try
            {
                using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(2));
                using NetworkStream stream = client.GetStream();

                // Read and discard the request line. Writing a response without draining the
                // request makes some clients report a connection reset instead of the status code.
                var scratch = new byte[512];
                _ = await stream.ReadAsync(scratch, timeout.Token).ConfigureAwait(false);

                string body = healthy ? "ok" : "degraded";
                string status = healthy ? "200 OK" : "503 Service Unavailable";
                byte[] response = Encoding.ASCII.GetBytes(
                    "HTTP/1.1 " + status + "\r\n"
                    + "Content-Type: text/plain; charset=utf-8\r\n"
                    + "Content-Length: " + body.Length.ToString(System.Globalization.CultureInfo.InvariantCulture) + "\r\n"
                    + "Connection: close\r\n"
                    + "\r\n"
                    + body);

                await stream.WriteAsync(response, timeout.Token).ConfigureAwait(false);
            }
            catch (IOException)
            {
                // The probe hung up. Nothing to report and nothing to do.
            }
            catch (ObjectDisposedException)
            {
            }
            catch (OperationCanceledException)
            {
            }
            catch (SocketException)
            {
            }
        }
    }

    public void Dispose()
    {
        if (_disposed)
        {
            return;
        }

        _disposed = true;

        try
        {
            _shutdown.Cancel();
            _listener.Stop();
        }
        catch (SocketException)
        {
        }

        try
        {
            _acceptLoop?.Wait(TimeSpan.FromSeconds(2));
        }
        catch (AggregateException)
        {
            // The accept loop unwinding through a cancelled socket is the expected shutdown path.
        }

        _shutdown.Dispose();
    }
}
