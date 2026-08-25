# syntax=docker/dockerfile:1
FROM mcr.microsoft.com/dotnet/sdk:8.0 AS build
WORKDIR /src
# Copy the project files first so the restore layer is cached across source-only changes.
COPY ClaimsEdi.sln .
COPY ClaimsEdi/ClaimsEdi.csproj ClaimsEdi/
COPY ClaimsEdi.Tests/ClaimsEdi.Tests.csproj ClaimsEdi.Tests/
RUN dotnet restore ClaimsEdi.sln
COPY . .
# Tests run in CI, not here: a test failure should fail a pipeline, not an image build that may be
# happening on a developer's laptop for an unrelated reason.
RUN dotnet publish ClaimsEdi/ClaimsEdi.csproj -c Release --no-restore -o /app/publish /p:UseAppHost=false

# runtime, not aspnet: the service has no web host. The health endpoint is forty lines of
# TcpListener, which is not worth the extra image and patch surface of ASP.NET Core.
FROM mcr.microsoft.com/dotnet/runtime:8.0
# SOC 2 CC6.1: the container runs as an unprivileged user with no shell-writable app dir.
RUN groupadd --system app \
    && useradd --system --gid app --no-create-home --shell /usr/sbin/nologin app
WORKDIR /app
COPY --from=build /app/publish/ /app/

# The claim spool is the only writable path. It must be a volume that outlives the container:
# the interchange control number sequence lives here, and a counter that restarts at 1 makes a
# trading partner reject every subsequent interchange as a duplicate.
RUN mkdir -p /var/lib/claims-edi/inbox /var/lib/claims-edi/outbox /var/lib/claims-edi/failed \
    && chown -R app:app /var/lib/claims-edi
VOLUME ["/var/lib/claims-edi"]

USER app
EXPOSE 8080

# The probe is the application itself, so the image needs neither curl nor wget — neither ships in
# the runtime image, and adding one adds a package and its CVE stream to every deployment.
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD ["dotnet", "/app/ClaimsEdi.dll", "healthcheck"]

ENTRYPOINT ["dotnet", "/app/ClaimsEdi.dll"]
CMD ["serve"]
