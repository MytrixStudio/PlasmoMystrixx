# Prompt para solucionar definitivamente el audio trabado de grupos en Puzzzleequipos

Actua como desarrollador senior de Java, Forge 1.20.1, Plasmo/MytrixVoice, audio Opus, UDP, OpenAL y arquitectura cliente-servidor de mods de Minecraft.

## Contexto

El voice chat de proximidad funciona bien.

El sistema nativo de grupos de MytrixVoice funciona bien cuando se usa manualmente:

```text
/groups create a
/groups join a
```

Pero cuando `puzzzleequipos` crea sus grupos de voz automaticamente, algunos jugadores escuchan la voz trabada, robotica, distorsionada o entrecortada.

Esto demuestra que el codec, OpenAL, UDP y la reproduccion no espacial de grupos no son la causa principal, porque el flujo nativo `/groups` suena bien. La falla esta en la integracion del minijuego o en el modo especial que convierte audio de proximidad en audio de grupo.

## Objetivo

Modificar MytrixVoice y Puzzzleequipos para que el minijuego use exactamente la ruta nativa de grupos de MytrixVoice, no una ruta paralela ni una conversion server-side de paquetes de proximidad.

La solucion final debe:

- Usar `MytrixVoiceGroupService`.
- Usar la activacion nativa `VoiceActivation.GROUP_ID`.
- No reenviar paquetes `PROXIMITY_ID` como si fueran grupo.
- No aumentar la distancia como solucion.
- No recodificar audio.
- No decodificar audio en el servidor.
- No modificar bytes Opus.
- No crear otro sistema de grupos.
- No duplicar audio por proximidad y grupo.
- Permitir que Puzzzleequipos meta jugadores automaticamente sin invitaciones.

## Archivos importantes del proyecto MytrixVoice

Revisar primero:

```text
server/common/src/main/java/su/plo/voice/server/group/MytrixVoiceGroupService.java
server/common/src/main/java/su/plo/voice/server/command/VoiceGroupsCommand.java
server/common/src/main/kotlin/su/plo/voice/server/audio/capture/GroupServerActivation.kt
server/common/src/main/kotlin/su/plo/voice/server/audio/capture/ProximityServerActivation.kt
server-proxy-common/src/main/kotlin/su/plo/voice/server/audio/capture/VoiceServerActivationManager.kt
server/common/src/main/java/su/plo/voice/server/dynamic/DynamicVoiceService.java
server/common/src/main/java/su/plo/voice/server/BaseVoiceServer.java
client/src/main/java/su/plo/voice/client/audio/capture/VoiceAudioCapture.java
client/src/main/java/su/plo/voice/client/audio/capture/VoiceClientActivationManager.java
client/src/main/java/su/plo/voice/client/audio/capture/VoiceClientActivation.java
client/src/main/kotlin/su/plo/voice/client/audio/source/BaseClientAudioSource.kt
client/src/main/kotlin/su/plo/voice/client/audio/source/ClientDirectSource.kt
client/src/main/kotlin/su/plo/voice/client/audio/source/VoiceClientSourceManager.kt
```

## Archivo importante de Puzzzleequipos

```text
E:/herrramientas/puzzzleequipos/src/main/java/net/mytrix/puzzzleequipos/voice/TeamVoiceBridge.java
```

## Diagnostico esperado

El problema aparece porque `TeamVoiceBridge` usa la API dinamica de MytrixVoice para:

```text
setProximityGroupReplacementEnabled(true)
selectGroupChannel(playerId, channelId)
syncMembers(channelId, players)
```

Ese flujo convierte paquetes de proximidad normales en paquetes de grupo en el servidor.

El flujo bueno de `/groups` no hace eso. El flujo bueno registra un grupo nativo en `MytrixVoiceGroupService`, sincroniza un canal privado y el cliente envia paquetes con la activacion real de grupo.

Por eso la solucion no debe seguir intentando arreglar la conversion `PROXIMITY -> GROUP`. La solucion debe hacer que Puzzzleequipos use el grupo nativo.

## Diseno requerido

### 1. Agregar API administrativa nativa en MytrixVoiceGroupService

Agregar metodos publicos server-side, sin depender de que un jugador ejecute el comando:

```java
public GroupRecord createOrUpdateManagedGroup(
        String ownerNamespace,
        String groupName,
        Collection<UUID> members,
        boolean persistent
);

public GroupRecord syncManagedGroup(
        String ownerNamespace,
        String groupName,
        Collection<UUID> members
);

public boolean deleteManagedGroup(
        String ownerNamespace,
        String groupName
);

public boolean removeManagedMember(
        String ownerNamespace,
        String groupName,
        UUID playerId
);
```

Reglas:

- Debe reutilizar `createOrUpdateChannel(GroupRecord group)`.
- Debe actualizar `groupsById`.
- Debe actualizar `groupIdByMember`.
- Debe llamar `dynamicVoiceService().selectGroupChannel(playerId, channelId(group))`.
- Debe llamar `dynamicVoiceService().clearGroupChannelSelection(playerId)` cuando se quite un jugador.
- Debe cerrar fuentes de grupo cuando un jugador salga.
- Debe guardar solo si el grupo es persistente.
- Debe ser idempotente.
- Debe ser thread-safe.

El grupo gestionado debe tener metadata que identifique al propietario:

```text
owner_namespace=puzzzleequipos
managed=true
```

### 2. Agregar comandos admin en /groups

En `VoiceGroupsCommand.java`, agregar comandos OP:

```text
/groups admin sync <owner> <grupo> <jugador|@a|selector>
/groups admin add <owner> <grupo> <jugador|@a|selector>
/groups admin remove <owner> <grupo> <jugador|@a|selector>
/groups admin delete <owner> <grupo>
/groups admin inspect <owner> <grupo>
```

Estos comandos deben llamar a los metodos nuevos de `MytrixVoiceGroupService`.

No deben usar invitaciones.

No deben requerir que los jugadores acepten.

No deben crear canales en la API dinamica manual antigua.

El selector `@a[team=...]` debe resolver jugadores usando el CommandSourceStack real cuando sea posible. Si la capa `McCommand` no expone selectores vanilla, implementar resolucion minima:

- `@a`
- nombre exacto
- UUID

y documentar que Puzzzleequipos puede pasar lista de nombres/UUID.

### 3. Cambiar TeamVoiceBridge en Puzzzleequipos

En `TeamVoiceBridge.java`, dejar de preferir `DynamicApiBridge` para crear canales de puzzle.

Cambiar:

```java
if (apiBridge.available()) {
    syncTeamViaApi(team, requestedMembers, muted);
    return;
}
syncTeamViaCommands(team, requestedMembers, muted);
```

por un driver que use primero los grupos nativos:

```java
if (nativeGroupsBridge.available()) {
    syncTeamViaNativeGroups(team, requestedMembers, muted);
    return;
}

if (commandAvailable()) {
    syncTeamViaNativeGroupCommands(team, requestedMembers, muted);
    return;
}

// Solo como ultimo fallback:
if (apiBridge.available()) {
    syncTeamViaApi(team, requestedMembers, muted);
}
```

Pero el fallback API dinamico debe estar desactivado por defecto mientras se depura, porque es el flujo que causa la voz trabada.

Tambien cambiar:

```java
driver.enableGroupReplacement();
```

No debe llamar `setProximityGroupReplacementEnabled(true)` si se usa el grupo nativo.

### 4. Si se necesita que la tecla normal hable por grupo

No hacerlo con conversion server-side de paquetes de proximidad.

Hacerlo en el cliente:

- El servidor sincroniza que el jugador tiene un canal de grupo seleccionado.
- El cliente, al detectar que debe hablar por grupo, envia `PlayerAudioPacket` con `VoiceActivation.GROUP_ID`.
- Si el canal es exclusivo, el cliente no envia el paquete de proximidad para esa misma captura.
- Esto evita doble audio y evita que el servidor tenga que reinterpretar un paquete `PROXIMITY_ID`.

El cambio debe hacerse en:

```text
client/src/main/java/su/plo/voice/client/audio/capture/VoiceAudioCapture.java
client/src/main/java/su/plo/voice/client/audio/capture/VoiceClientActivationManager.java
```

La politica debe ser:

```text
si hay grupo seleccionado y modo replace activo:
    enviar solo activacion GROUP
    no enviar activacion PROXIMITY
si no:
    proximidad normal
```

No crear un segundo encoder ni capturar el microfono dos veces.

### 5. Validacion de audio

Agregar logs temporales con rate limit:

```text
speaker UUID
group id
sequenceNumber
activationId
payload size
CRC32 payload
thread
recipient count
source id
source state
```

Comparar:

- `/groups create a`
- grupo creado por Puzzzleequipos

El flujo corregido debe mostrar que Puzzzleequipos usa `VoiceActivation.GROUP_ID`, no `VoiceActivation.PROXIMITY_ID` convertido.

### 6. Pruebas manuales

1. Entrar con 3 jugadores.
2. Crear grupo nativo manual con `/groups create a` y confirmar audio limpio.
3. Iniciar carrera de Puzzzleequipos.
4. Confirmar que Puzzzleequipos crea/sincroniza grupos mediante `MytrixVoiceGroupService`.
5. Confirmar que A y B se escuchan lejos y entre dimensiones.
6. Confirmar que C fuera del equipo no escucha.
7. Confirmar que cerca no hay audio duplicado.
8. Confirmar que al terminar la carrera los jugadores salen del grupo.
9. Confirmar que la proximidad normal vuelve a funcionar.

### 7. Criterio de exito

La solucion esta correcta cuando:

- `/groups create a` y los grupos de Puzzzleequipos usan la misma ruta interna.
- Puzzzleequipos no usa conversion temprana de proximidad a grupo.
- No se modifica el payload Opus.
- No se recodifica.
- No se decodifica en servidor.
- No se comparte decoder/jitter/sequence entre jugadores.
- La voz grupal no usa distancia.
- El audio deja de sonar trabado.
- La proximidad normal sigue funcionando.

## Comandos de build

MytrixVoice Forge 1.20.1:

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat --no-daemon :server:common:test :server-proxy-common:test :client:1.20.1-forge:build
```

Puzzzleequipos:

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat --no-daemon build
```
