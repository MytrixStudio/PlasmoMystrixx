# MytrixVoice 3.1.11 - mejora de comandos /vcgroup

## Cambios

- `/vcgroup add` y `/vcgroup forcejoin` agregan jugadores directamente al grupo sin invitaciones.
- `add`, `forcejoin`, `setup`, `select`, `remove`, `forceleave`, `unselect` y `leave-all` aceptan jugadores individuales y selectores.
- Selectores soportados: `@a`, `@a[team=rojo]`, `@a[team=!rojo]`, `@a[team=]` y `@a[team=!]`.
- El filtro `team` usa el equipo real del scoreboard del jugador; las sugerencias intentan leer todos los equipos registrados del scoreboard y usan los equipos online como respaldo.
- TAB sugiere subcomandos por permiso, grupos existentes, jugadores conectados, plantillas de selector y equipos.
- Los comandos administrativos requieren consola/bloque de comandos o permiso `mytrixvoice.admin` / `mytrixvoice.groups.manage`.
- Los resultados de operaciones masivas muestran encontrados, agregados/retirados, omitidos, fallos y selección automática.

## Compatibilidad

- No cambia el protocolo de audio.
- No modifica `/groups`.
- Conserva `/vcgroup replace on`.
- Conserva el fix 3.1.11 de enrutamiento nativo de grupo para Puzzzleequipos.
