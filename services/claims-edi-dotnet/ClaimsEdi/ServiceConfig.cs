using System;
using System.Globalization;

namespace Firmus.Interop.ClaimsEdi;

/// <summary>
/// Everything the worker reads from its environment.
/// </summary>
/// <remarks>
/// Read once, at start-up, and validated there. Configuration that is read lazily at the point of
/// use fails on the first claim rather than on the first second, which in a batch service means the
/// failure surfaces after the queue has already accepted work.
/// </remarks>
public sealed class ServiceConfig
{
    private ServiceConfig(
        string inputDirectory,
        string outputDirectory,
        string failedDirectory,
        string controlNumberFile,
        TimeSpan pollInterval,
        int healthPort,
        string? queueUrl,
        string? awsRegion,
        X12Delimiters delimiters,
        X12DelimiterPolicy delimiterPolicy,
        TradingPartnerProfile profile)
    {
        InputDirectory = inputDirectory;
        OutputDirectory = outputDirectory;
        FailedDirectory = failedDirectory;
        ControlNumberFile = controlNumberFile;
        PollInterval = pollInterval;
        HealthPort = healthPort;
        QueueUrl = queueUrl;
        AwsRegion = awsRegion;
        Delimiters = delimiters;
        DelimiterPolicy = delimiterPolicy;
        Profile = profile;
    }

    public string InputDirectory { get; }

    public string OutputDirectory { get; }

    public string FailedDirectory { get; }

    public string ControlNumberFile { get; }

    public TimeSpan PollInterval { get; }

    public int HealthPort { get; }

    /// <summary>Null runs the dry-run publisher, mirroring the upstream service's in-memory sink.</summary>
    public string? QueueUrl { get; }

    public string? AwsRegion { get; }

    public X12Delimiters Delimiters { get; }

    public X12DelimiterPolicy DelimiterPolicy { get; }

    public TradingPartnerProfile Profile { get; }

    public static ServiceConfig FromEnvironment()
    {
        var delimiters = new X12Delimiters(
            Delimiter("X12_ELEMENT_SEPARATOR", X12Delimiters.DefaultElement),
            Delimiter("X12_COMPONENT_SEPARATOR", X12Delimiters.DefaultComponent),
            Delimiter("X12_REPETITION_SEPARATOR", X12Delimiters.DefaultRepetition),
            Delimiter("X12_SEGMENT_TERMINATOR", X12Delimiters.DefaultSegment));

        string policyText = Text("X12_DELIMITER_POLICY", "reject");
        X12DelimiterPolicy policy = policyText.ToUpperInvariant() switch
        {
            "REJECT" => X12DelimiterPolicy.Reject,
            "STRIP" => X12DelimiterPolicy.Strip,
            _ => throw new ArgumentException(
                $"X12_DELIMITER_POLICY is '{policyText}'; expected 'reject' or 'strip'."),
        };

        string usageIndicator = Text("X12_USAGE_INDICATOR", "T").ToUpperInvariant();
        if (usageIndicator != "T" && usageIndicator != "P")
        {
            throw new ArgumentException(
                $"X12_USAGE_INDICATOR is '{usageIndicator}'; expected 'T' (test) or 'P' (production). There is no safe default for this, which is why it is validated rather than coerced.");
        }

        var billingProvider = new BillingProviderProfile(
            Text("BILLING_PROVIDER_NAME", "FIRMUS HEALTH GROUP"),
            Text("BILLING_PROVIDER_NPI", "1234567893"),
            Text("BILLING_PROVIDER_TAX_ID", "581234567"),
            Text("BILLING_PROVIDER_TAXONOMY", "207Q00000X"),
            Text("BILLING_PROVIDER_ADDRESS", "1 HOSPITAL WAY"),
            Text("BILLING_PROVIDER_CITY", "ATLANTA"),
            Text("BILLING_PROVIDER_STATE", "GA"),
            Text("BILLING_PROVIDER_POSTAL_CODE", "303011234"));

        var payer = new PayerProfile(
            Text("PAYER_NAME", "ACME HEALTH PLAN"),
            Text("PAYER_ID", "60054"),
            Text("PAYER_CLAIM_FILING_INDICATOR", "CI"));

        var profile = new TradingPartnerProfile(
            Text("X12_SENDER_QUALIFIER", "ZZ"),
            Text("X12_SENDER_ID", "FIRMUSHEALTH"),
            Text("X12_RECEIVER_QUALIFIER", "ZZ"),
            Text("X12_RECEIVER_ID", "CLEARINGHOUSE"),
            usageIndicator,
            Text("SUBMITTER_NAME", "FIRMUS HEALTH GROUP"),
            Text("SUBMITTER_ID", "FIRMUS01"),
            Text("SUBMITTER_CONTACT_NAME", "EDI OPERATIONS"),
            Text("SUBMITTER_CONTACT_PHONE", "4045550100"),
            Text("RECEIVER_NAME", "ACME CLEARINGHOUSE"),
            Text("RECEIVER_ETIN", "CH0001"),
            billingProvider,
            payer);

        return new ServiceConfig(
            Text("EDI_INPUT_DIR", "/var/lib/claims-edi/inbox"),
            Text("EDI_OUTPUT_DIR", "/var/lib/claims-edi/outbox"),
            Text("EDI_FAILED_DIR", "/var/lib/claims-edi/failed"),
            Text("EDI_CONTROL_NUMBER_FILE", "/var/lib/claims-edi/control-number"),
            TimeSpan.FromSeconds(Integer("EDI_POLL_INTERVAL_SECONDS", 5, 1, 3600)),
            Integer("HEALTH_PORT", 8080, 1, 65535),
            Optional("SQS_QUEUE_URL"),
            Optional("AWS_REGION"),
            delimiters,
            policy,
            profile);
    }

    private static string Text(string name, string fallback) =>
        Optional(name) ?? fallback;

    private static string? Optional(string name)
    {
        string? value = Environment.GetEnvironmentVariable(name);
        return string.IsNullOrWhiteSpace(value) ? null : value.Trim();
    }

    private static int Integer(string name, int fallback, int minimum, int maximum)
    {
        string? raw = Optional(name);
        if (raw is null)
        {
            return fallback;
        }

        if (!int.TryParse(raw, NumberStyles.None, CultureInfo.InvariantCulture, out int value)
            || value < minimum
            || value > maximum)
        {
            throw new ArgumentException(
                $"{name} is '{raw}'; expected a whole number between {minimum.ToString(CultureInfo.InvariantCulture)} and {maximum.ToString(CultureInfo.InvariantCulture)}.");
        }

        return value;
    }

    /// <summary>
    /// Reads a delimiter, accepting the usual two-character escapes so a newline terminator can be
    /// expressed in a Kubernetes manifest.
    /// </summary>
    private static char Delimiter(string name, char fallback)
    {
        string? raw = Environment.GetEnvironmentVariable(name);
        if (string.IsNullOrEmpty(raw))
        {
            return fallback;
        }

        switch (raw)
        {
            case "\\n":
                return '\n';
            case "\\r":
                return '\r';
            case "\\t":
                return '\t';
            default:
                if (raw.Length != 1)
                {
                    throw new ArgumentException(
                        $"{name} is '{raw}'; a delimiter is exactly one character (or the escape \\n, \\r or \\t).");
                }

                return raw[0];
        }
    }
}
