# Flujo de contribución

Cada implementación comienza en una rama propia desde `main` actualizada. Todos los comandos
de esta guía son para Git Bash. No trabajar ni hacer push directo sobre `main`.

```bash
git switch main
git pull --ff-only origin main
git switch -c feat/ingestion-webhook
# Implementar y ejecutar las pruebas correspondientes.
git add src
git diff --cached --check
git diff --cached
git commit -m 'feat: agregar webhook de ingesta'
git push -u origin feat/ingestion-webhook
```

Crear pull request hacia `main`, indicar problema, comportamiento y pruebas. Prefijos de rama:
`feat/`, `fix/`, `chore/`, `docs/`, `test/`, `refactor/`; descripción en minúsculas separada por guiones.

Los commits y el título del PR usan Conventional Commits: `feat:`, `fix:`, `chore:`, `docs:`,
`test:`, `refactor:`; también se admiten `perf:`, `build:`, `ci:` y `revert:` en títulos.

CI ejecuta `mvn verify` con Java 21 y PostgreSQL 16; las variables `FINANCE_TEST_DB_*` habilitan
las pruebas reales de infraestructura. Los checks requeridos previstos son `verify` y `conventions`.
El título del PR determina el mensaje del squash commit. No usar `pull_request_target` para ejecutar
código de ramas externas ni dar permisos de escritura al workflow de pruebas.

## Merge

- Autorizar el merge explícitamente después de revisar el diff y las pruebas.
- Usar squash merge. No activar auto-merge sin autorización.
- Resolver conversaciones abiertas y exigir checks aprobados y rama actualizada.
- Prohibir force push y borrado de `main`; aplicar reglas también al administrador.
- Si solo hay un colaborador, no exigir aprobación de un segundo usuario: el autor no puede
  aprobar su propio PR. La autorización de merge sigue siendo necesaria.
- Eliminar la rama remota al fusionar. Cada tarea siguiente usa una nueva rama.
- No publicar `.env`, API Keys, contraseñas, tokens, logs, datos financieros ni `target/`.

Tras fusionar, actualizar la copia local:

```bash
git switch main
git pull --ff-only origin main
# Ejecutar solo cuando la rama esté integrada en el remoto:
git branch -d feat/ingestion-webhook
```

Si el merge se hizo mediante squash, Git puede rechazar `branch -d` porque los commits originales
no son ancestros de `main`. Conservar la rama local o verificar antes de eliminarla; no forzar
borrado de una rama sin revisar que su contenido esté incorporado.

## Bootstrap

El primer commit de `main` solo inicializa Git. La base existente y esta configuración se
publican en `chore/bootstrap-repository` para revisión mediante el primer PR. La implementación
del webhook comenzará después de integrar esa base, en `feat/ingestion-webhook`.
