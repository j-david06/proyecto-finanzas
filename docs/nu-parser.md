# Parser Nu Colombia

Plantillas basadas en las cinco muestras anonimizadas proporcionadas. No se leen notificaciones
push de iOS. Los movimientos diarios entran desde Shortcuts con texto/OCR de captura o comprobante
compartido; Apple Pay puede continuar enviando NORMALIZED. El registro manual sigue pendiente.

## Canales

- APP_DETAIL + RAW_MESSAGE: detalle de la app Nu (Bre-B recibido/enviado, compra y cancelación).
- EMAIL + RAW_MESSAGE: exclusivamente la muestra de pago de tarjeta, como TRANSFER.
- Los correos de corte/extracto no son compras. El [worker de Gmail](email-worker.md) registra
  la plantilla de extracto como facturación; otros formatos quedan en REVIEW.
- La conexión Gmail por IMAP está disponible y desactivada hasta configurarla. Nu no envía un correo por cada movimiento
  diario, según el comportamiento indicado por el usuario. No hay lectura de pushes de terceros.

## Reglas

1. Recibiste de / Transferencia Bre-B aceptada: INCOME, contraparte como comercio/concepto.
2. Transferencia inmediata Bre-B enviada: EXPENSE por Monto total; tax_4x1000 separado como
   metadato, incluido en ese total. El total debe coincidir con cabecera o cabecera + impuesto.
3. Compra: EXPENSE / POSTED. La repetición del importe en Cuotas no es otro gasto. El número
   de cuotas se conserva solo si cantidad × importe de cuota coincide exactamente con el total.
4. Estado Cancelada: EXPENSE / VOIDED, COP y original_amount/original_currency USD cuando están
   presentes. No entra en gastos liquidados; no se cambia a POSTED ni se inventa un reembolso.
5. En esa misma plantilla de tarjeta, Estado Reembolsada o Reembolso produce INCOME / POSTED.
   Estos estados son variantes explícitas; otros textos de devolución requieren nueva plantilla.
6. Pago por correo: un único TRANSFER desde DEPOSIT a CREDIT_CARD, misma moneda y propietario.
   ID transacción se conserva como bank_event_id con bank_provider NU_CO. El movimiento se fecha
   con Lo has realizado el, incluyendo PM/AM; no con la recepción del correo ni con la fecha de corte.

Fechas aceptadas: 13 nov 2026, 12:36; 12 de noviembre de 2026, 13:10;
16:28, Domingo, 8 de Noviembre de 2026; 12 noviembre 2026 10:31:38 PM.
Zona America/Bogota, calendario estricto y día de semana consistente cuando aparece.

Importes Nu: punto para miles y coma para centavos, nunca conversión mediante double/float.
$ identifica COP dentro de una plantilla Nu; US$ identifica el importe original USD separado.
El impuesto puede ser cero. No se toman referencias de llave Bre-B, últimos cuatro dígitos,
comercio o cuotas como identificador bancario. No se guardan PAN completos ni la llave Bre-B.

Los campos repetidos contradictorios, textos extra, mensajes concatenados, OCR incompleto,
fechas inválidas, formatos no reconocidos o comisiones no modeladas requieren REVIEW.
No se utiliza reconocimiento aproximado ni un algoritmo que tome el primer monto encontrado.

## Contrato

```json
{
  "schema_version": 1,
  "trigger": "APP_DETAIL",
  "format": "RAW_MESSAGE",
  "account_id": "UUID-CUENTA-ORIGEN",
  "message": {
    "text": "TEXTO COMPLETO CON SALTOS DE LINEA",
    "received_at": "2026-11-13T16:00:00-05:00"
  }
}
```

Para pago de tarjeta: trigger EMAIL y destination_account_id con UUID explícito.
Ambas cuentas deben estar activas y en allowed_account_ids. La cuenta origen DEPOSIT debe
estar configurada con bank_reference igual al Producto origen del recibo; la cuenta destino
CREDIT_CARD con card_last4 igual al Producto destino. Mapeo ausente/contradictorio:
REVIEW / ACCOUNT_MAPPING_REQUIRED. Nunca se infiere una cuenta a partir de nombre o importe.
Las autorizaciones y los mapeos se revalidan bajo bloqueos en el commit financiero.

GET /api/v1/ingestions/{id} añade transaction_status para distinguir POSTED y VOIDED aunque
ambos se hayan procesado correctamente. V2 añade metadatos a transactions y mapeos a accounts;
V1 permanece intacta. original_amount y original_currency deben aparecer juntos.

## Probar desde Git Bash

```bash
bash scripts/run-demo.sh
```

Abrir http://127.0.0.1:8090/demo/, cargar .env.demo-panel y seleccionar Texto Nu / OCR.
Pegar una de las muestras completas con saltos de línea, enviar y consultar el estado.
Para la muestra de pago elegir Correo de pago de tarjeta. La demo aprovisiona su propia cuenta
CREDIT_CARD con últimos cuatro dígitos ficticios 9876 y origen ficticio ABC123; añade el UUID
INGESTION_DESTINATION_ACCOUNT_ID a .env.demo-panel y lo carga al importar el archivo.
Esto ocurre exclusivamente en finanzas_demo, con las mismas guardas loopback del aprovisionamiento.
No aplica esos mapeos ficticios a la base personal finanzas.

## Idempotencia y límites

Repetir el mismo evento exige los mismos bytes y el mismo Idempotency-Key. Solo el correo
proporciona una referencia bancaria estable que permite deduplicar dos ingestas distintas.
Una captura Bre-B/tarjeta con una clave nueva es otra observación: no existe deduplicación
heurística por monto, fecha y comercio. Conservar la clave al reintentar desde Shortcuts.

VOIDED registra la observación cancelada. Sin referencia de operación original no modifica
una compra POSTED anterior; ese enlace y la conciliación manual quedan pendientes. Igualmente,
un reembolso explícito crea su propio ingreso, sin sobrescribir el gasto original.

La marca Nu en el texto no prueba autenticidad bancaria. La API Key autentica al cliente.
El adaptador Gmail valida origen y cuenta conectada por separado; ver [configuración](email-worker.md).
No hay saldos, categorías automáticas, OCR en el backend ni ejecución de pagos bancarios.

## Validación

Fixtures de prueba anonimizados en src/test/resources/nu. Incluyen las cinco estructuras;
las variantes de reembolso/cuotas son sintéticas y se identifican en sus pruebas.
Pruebas de parser, calendario, metadatos de dominio, actualización V1 -> V2, persistencia de
VOIDED y USD, transferencias con allowlist/mapeo, conflictos y deduplicación exacta bancaria.
