# MytrixVoice 3.1.11 — corrección de voz grupal para Puzzzleequipos

## Problema

El modo de Puzzzleequipos activaba `/vcgroup replace on`. En 3.1.10, el cliente enviaba audio como proximidad y el servidor reinterpretaba esos paquetes como grupo. Esa ruta era distinta del flujo nativo de grupos y podía producir secuencias/estados de activación inconsistentes, audio entrecortado o una fuente antigua que permanecía activa al cambiar de grupo.

## Solución

La sustitución ahora ocurre antes de enviar el audio:

1. El servidor sincroniza al cliente si tiene un canal de grupo seleccionado y el modo `replace` está activo.
2. La captura de micrófono usa la tecla normal, pero envía el paquete con `VoiceActivation.GROUP_ID`.
3. La misma captura se codifica una sola vez.
4. No se envía `VoiceActivation.PROXIMITY_ID` ni ninguna otra activación de proximidad durante ese modo.
5. El servidor procesa el paquete por la activación nativa de grupo, igual que el sistema normal de grupos.

No se decodifica, recodifica ni modifica el payload Opus en el servidor.

## Compatibilidad

Puzzzleequipos 2.10.20 puede seguir usando los mismos comandos:

```text
/vcgroup replace on
/vcgroup create <grupo>
/vcgroup add <grupo> <jugador>
/vcgroup leave-all <jugador>
/vcgroup replace off
```

No necesita cambios de código para aprovechar esta corrección.

## Instalación obligatoria

Esta versión modifica el protocolo y la captura del cliente. Debe instalarse **MytrixVoice 3.1.11 tanto en el servidor como en todos los clientes**. No mezclar clientes 3.1.10 con un servidor 3.1.11.

## Prueba recomendada

1. Entrar con tres jugadores usando exactamente la misma versión.
2. Confirmar que la proximidad normal funciona antes de iniciar la carrera.
3. Iniciar Puzzzleequipos y confirmar que cada jugador escucha solo a su equipo.
4. Hablar simultáneamente durante 30–60 segundos.
5. Terminar o detener la carrera y confirmar que vuelve la proximidad normal.
6. Repetir después de reconectar un cliente para validar la resincronización.
