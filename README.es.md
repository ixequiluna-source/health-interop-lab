![Health Interop Lab](docs/assets/cover.svg)

# Health Interop Lab

**Los mensajes clínicos cambian de sistema. Su significado debe conservarse.**

Laboratorio de ingeniería para explorar ingestión HL7, proyecciones FHIR, consultas de pacientes y generación de reclamaciones X12. Su valor está en las decisiones verificables: cuándo confirmar un mensaje, cómo rechazar un evento obsoleto y cómo detectar un sobre EDI incorrecto.

[English](README.md) · [Arquitectura](docs/ARCHITECTURE.md) · [Recorrido por el código](docs/ENGINEERING-TOUR.md) · [Autor](https://ixequiluna.ai)

| Componente | Qué demuestra |
| --- | --- |
| Java | Parsing ER7, transporte MLLP y semántica de ACK. |
| Kotlin | Mapeo a FHIR R4, proyecciones y manejo de eventos obsoletos. |
| Go | Consultas gRPC y paginación determinista. |
| C# | Lectura, escritura y validación de sobres X12 837P. |
| Angular | Búsqueda, detalle de pacientes y estados con datos sintéticos. |
| Infraestructura | Controles declarativos y verificaciones automatizadas. |

## Explóralo sin nube

Con Node.js 22 y npm:

```sh
git clone https://github.com/ixequiluna-source/health-interop-lab.git
cd health-interop-lab/web/console-angular
npm ci
npm start
```

Abre **http://localhost:4200**. El modo de desarrollo usa datos sintéticos en memoria; la pantalla de pipeline no es monitoreo de un sistema real. Busca un paciente, abre su detalle y recorre los resultados.

## Qué falta para una integración completa

La consola ya incluye un adaptador HTTP delante del servicio gRPC. La [demo conectada reproducible](docs/CONNECTED-DEMO.md) recorre búsqueda, detalle y encuentros con datos sintéticos. El worker de reclamaciones recibe archivos JSON con cargos; no consulta directamente el gateway. La ingestión HL7 hasta la consola y la entrega de reclamaciones siguen pendientes de una prueba integrada.

Consulta los [límites y evidencias de la revisión](docs/REVIEW-2026-09-19.md). Las pruebas de controles no constituyen certificación SOC 2 ni validación para uso clínico. No se despliega infraestructura al explorar la consola.

Mantenido por **Dr. Ixequi Luna**.
