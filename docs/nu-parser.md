# Parser Nu: preparación pendiente de muestras

Estado: extractores de valores implementados y probados; **sin plantillas Nu verificadas ni
integración de mensajes RAW_MESSAGE**. El worker sigue enviando esos mensajes a
`REVIEW / PARSER_UNAVAILABLE`. No está listo para fusionar como parser funcional.

## Implementado

- `ColombianPesoParser`: importes COP con `BigDecimal`, máximo numeric(18,2), positivos,
  sin redondeo ni coma flotante. Requiere marca COP explícita. Una plantilla verificada
  podrá establecer la moneda de un importe que solo contenga `$`.
- Agrupación completa colombiana (`COP $ 45.900,50`) o internacional (`$45,900.50 COP`),
  números sin agrupación, decimales exactos y espacios Unicode entre moneda e importe.
  Rechaza agrupación irregular, signos, texto extra, importes múltiples y precisión inválida.
- `MessageTimestampParser`: ISO con offset o fecha/hora completa `dd/MM/uuuu HH:mm:ss`
  bajo contrato explícito de hora de Bogotá. Calendario estricto; no sustituye fecha del
  movimiento por `message.received_at`.
- Pruebas de formatos válidos, importes límites y entradas ambiguas/malformadas.

Los ejemplos de importes anteriores son datos sintéticos de pruebas, no plantillas de Nu.

## Información necesaria

Se necesitan 2–3 mensajes transaccionales Nu Colombia representativos: compra e ingreso,
con redacción, saltos de línea, monto y fecha intactos en su formato. Sustituir nombres,
cuentas, tarjetas y referencias por valores ficticios. No enviar credenciales, códigos OTP,
números completos de tarjeta/cuenta ni datos financieros personales.

Con las muestras se implementarán plantillas versionadas, selección de tipo de movimiento,
extracción de comercio y referencia bancaria cuando exista, y pruebas end-to-end de
`RAW_MESSAGE -> PROCESSED` más rechazo de formatos desconocidos o contradictorios.

La marca Nu en el texto no autentica el origen. La API Key autentica al cliente de ingesta;
el futuro adaptador de correo deberá validar su conexión/origen por separado. Nunca deduplicar
canales por monto/fecha/comercio ni inventar una referencia bancaria.
