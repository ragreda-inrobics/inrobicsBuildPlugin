# Versiones de Unity

Care y Virtual tienen builds independientes. Las rutas locales siguen siendo las de `unityLibraryPathCare` y `unityLibraryPathVirtual` en `gradle.properties`.

## Publicar desde Unity

En Scenes & Builds Manager, `Export completo + Google Drive` crea un identificador único por exportación y publica:

```text
androidBuild:Builds/versions/<Care|Virtual>/<buildId>/
  androidBuild<Care|Virtual>.zip
  commit_info.txt
  latest.json
```

Solo al completar la subida se actualiza `Builds/dev/<flavor>/latest.json`. Los metadatos incluyen commit, fecha UTC, flavor e identificador. Los metadatos externos incluyen también el SHA-256 del ZIP. Se mantiene `unityLibrary/version.txt` para el código Android existente y se añade `unityLibrary/unity-build.json` para identificar la exportación instalada.

`Promocionar dev -> master` apunta master a esa exportación exacta, sin recompilar ni modificar el ZIP. Las exportaciones posteriores conservan las anteriores.

## Fijar la versión de una release Android

Después de publicar y promocionar una build con el nuevo formato, desde el proyecto Android:

```powershell
.\download_unity_build.ps1 Care -Channel Master -Pin
.\download_unity_build.ps1 Virtual -Channel Master -Pin
```

Estos comandos descargan la estable y fijan su identificador en `unity-builds.json`. Incluye este archivo en el commit de la rama master/release de Android. Cada rama mantiene sus propios identificadores; publicar otra estable no modifica los ya fijados. También puedes editar los identificadores directamente.

El archivo se ha inicializado con identificadores vacíos: no se asignan versiones inventadas a los ZIP antiguos. Publica una nueva build para empezar a utilizar el flujo.

## Descargar

```powershell
# Desarrollo diario; no modifica los identificadores fijados
.\download_unity_build.ps1 Care
.\download_unity_build.ps1 Virtual -Channel Dev

# Versión exacta fijada en la rama actual
.\download_unity_build.ps1 Care -Channel Defined
```

La descarga requiere rclone configurado con el remote `androidBuild`. Comprueba su código de salida, SHA-256, las rutas del ZIP y los metadatos internos antes de sustituir la exportación. Si falla antes de instalar, conserva la build anterior. Una sustitución fallida intenta restaurarla. Si tampoco puede restaurarla, conserva el respaldo e indica su ubicación.

## Plugin Android Studio

La sección Unity está arriba, separada con una línea, y aparece para Care/Virtual. Permite descargar la versión definida o última dev. Compara los identificadores locales al cambiar flavor, al finalizar una descarga y cada cinco segundos, sin acceso a Drive. La discrepancia es un aviso y no bloquea la firma. Descargar dev no cambia `unity-builds.json`.

La salida aparece en la consola del menú. Si Gradle necesita releer los módulos después de sustituirlos, sincroniza el proyecto.

El botón estable se llama `Descargar versión master definida`. Dev muestra `Descargar última dev` solo cuando hay una build nueva; en caso contrario muestra `Build dev actualizada` y queda deshabilitado. Si todavía no puede conocer el estado, muestra `Comprobar build dev`. El estado se comprueba automáticamente, sin botón de actualización. Antes de descargar dev (también desde una notificación), se consulta de nuevo el remoto y se releen los metadatos instalados para evitar descargar una build ya instalada.

Al abrir el proyecto, el plugin comprueba dev de Care y Virtual en segundo plano. Solo muestra una notificación con botón de descarga cuando encuentra un identificador distinto y, si ambas fechas están disponibles, más reciente que la exportación instalada. No muestra notificaciones por falta de conexión o de rclone. La comprobación tiene un límite de 30 segundos por flavor y no cambia las versiones fijadas.
