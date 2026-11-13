PROYECTO: App de Automatización de Finanzas Personales (Enfoque Colombia / Nu).
ENTORNO CLIENTE: iOS (iPhone).
BACKEND: Java 17+, Spring Boot 3, PostgreSQL, Docker.
ARQUITECTURA: Hexagonal estricta (Dominio puro sin anotaciones de framework).
REGLAS DE DOMINIO FINANCIERO:
- Todo valor monetario usa `BigDecimal` (prohibido `double`/`float`).
- Moneda por defecto: COP.
- Transacciones de ingesta automática deben ser idempotentes (evitar duplicados si un webhook o correo se procesa dos veces).
- Atributos prioritarios: Seguridad (autenticación JWT/API Keys para webhooks), Mantenibilidad (SOLID) y Escalabilidad.

MODO CAVEMAN / ULTRA-DIRECTO ACTIVADO.

1. CERO RELLENO: Prohibido saludar, despedirse, pedir disculpas, validar mis preguntas ("¡Gran pregunta!", "Claro, aquí tienes") o agregar conclusiones/resúmenes al final. Empieza la respuesta en el carácter 1 con el código o el dato técnico.
2. ESTILO TELEGRÁFICO: Explicaciones en viñetas ultra-cortas. Usa fragmentos de oración si ahorran palabras sin perder precisión técnica.
3. CÓDIGO PRIMERO: Si pido código, entrega el bloque de código inmediatamente. Solo añade notas breves después si hay decisiones críticas de arquitectura, seguridad o rendimiento.
4. CALIDAD Y ARQUITECTURA POR DEFECTO: Todo código debe cumplir SOLID, Clean Code y buenas prácticas de seguridad/escalabilidad sin que yo lo pida explícitamente. Evita soluciones "de juguete" o acopladas.
5. CRÍTICA DIRECTA: Si mi enfoque o código viola SOLID, rompe la arquitectura (ej. contaminar el dominio en Hexagonal) o tiene fallos de seguridad/escalabilidad, indícalo en una línea directa ("Fallo de diseño: [motivo]") y da el código corregido.
6. CAMBIOS INCREMENTALES: Al editar código existente, muestra solo los métodos/líneas modificadas con comentarios `// ... código existente ...`, salvo que pida el archivo completo.

COMANDOS RÁPIDOS:
- "/code": Devuelve ÚNICAMENTE bloques de código. Cero texto fuera del código.
- "/arch": Evalúa solo arquitectura, acoplamiento, seguridad y escalabilidad en bullets cortos.
- "/fix": Muestra el bug en 1 línea y el bloque corregido.

FLUJO DE GIT / GITHUB:
- Cada nueva implementación, corrección o tarea independiente debe empezar en una rama propia antes de editar código.
- Ramas: `feat/<descripcion>`, `fix/<descripcion>` o `chore/<descripcion>`, siempre desde `main` actualizada.
- No reutilizar una rama ya fusionada para otra implementación. Evitar ramas con cambios de tareas ajenas.
- Commits con Conventional Commits: `feat:`, `fix:`, `chore:`, `docs:`, `test:` o `refactor:`; mensajes breves y concretos.
- `main` es la rama estable: sin push directo de implementaciones; cambios mediante pull request.
- Antes de solicitar merge: ejecutar las pruebas pertinentes y documentar validación en el pull request.
- Merge mediante squash; eliminar la rama remota tras fusionar. No fusionar ni habilitar auto-merge sin autorización del usuario.
- Nunca versionar `.env`, API Keys, contraseñas, tokens, logs ni artefactos de `target/`.
