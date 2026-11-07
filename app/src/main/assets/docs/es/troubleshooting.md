<!-- translation of docs/troubleshooting.md, sha256 b0f6df384e9e9b7d4967d3b674103bb633bf0b5264d966b3a14241405002f161 -->
<!-- en: troubleshooting -->
# Solución de problemas

Algo no funciona como debería. Esta página reúne los fallos que de verdad han
aparecido, con el motivo de cada uno y qué cambiar.

Hay tres documentos hermanos, y conviene saber cuál necesitas:

- **Esta página** responde a «algo no funciona»: un modelo que no se carga, una
  ejecución que se detuvo, un activador que nunca se disparó.
- [`faq.md`](faq.md) responde a «¿puede…?, ¿hace…?, ¿dónde está…?»: si algo se
  admite y dónde encontrarlo.
- [`user-guide.md`](user-guide.md) describe cómo funciona cada pantalla y cada
  función, y es la descripción de referencia del comportamiento de la app. Las
  otras dos enlazan a ella en lugar de repetirla.

La guía del usuario y los demás documentos enlazados desde aquí, salvo
«Preguntas frecuentes», se abren en el navegador y están en inglés. Sus
secciones se citan con el nombre del original.

<!-- en: contents -->
## Contenido

- [El modelo no se carga: «out of memory»](#the-model-fails-to-load-with-out-of-memory)
- [La inferencia es muy lenta](#inference-is-very-slow)
- [La app se congela mientras el modelo responde](#the-app-freezes-while-the-model-is-answering)
- [Una herramienta dice que no está disponible](#a-tool-says-it-is-unavailable)
- [Un servidor MCP está conectado, pero falta una herramienta que esperaba](#an-mcp-server-is-connected-but-a-tool-i-expect-isnt-there)
- [La misma pregunta va a la nube una vez y se queda en el dispositivo la siguiente](#the-same-question-goes-to-the-cloud-one-time-and-stays-local-the-next)
- [Una ejecución en segundo plano muere en cuanto salgo de la app](#a-background-run-dies-the-moment-i-leave-the-app)
- [Desapareció un pipeline](#a-pipeline-went-missing)
- [El agente se detuvo a mitad de una ejecución](#the-agent-stopped-mid-run)
- [Un activador no se disparó](#a-trigger-didnt-fire)
- [La búsqueda en memoria no encuentra un recuerdo evidente](#memory-search-isnt-finding-an-obvious-entry)
- [Al iniciar aparece «No se pueden desbloquear tus datos»](#your-data-cant-be-unlocked-appears-at-startup)
- [Falla la instalación sobre una copia de Google Play](#installing-over-a-copy-from-google-play-fails)
- [La descarga de un modelo se pausó tras cinco horas](#a-model-download-paused-after-five-hours)
- [Un modelo descargado no coincidió con su suma de comprobación](#a-downloaded-model-did-not-match-its-checksum)

---


<!-- en: the-model-fails-to-load-with-out-of-memory -->
## El modelo no se carga: «out of memory»

Los LLM locales necesitan un bloque grande de memoria contigua. Si un modelo no
se carga:

- Abre **Modelos** y toca **Activar** en un modelo más pequeño. Las variantes
  cuantizadas a 4 bits suelen caber donde no caben las de precisión completa.
- Si tienes varios modelos en el dispositivo, el que estaba activo sigue cargado
  hasta que activas otro. Cambiar de uno a otro varias veces puede dejar la
  memoria fragmentada: cerrar la app por completo (deslizándola fuera de la lista
  de apps recientes) y volver a abrirla libera el recurso nativo de forma fiable.
- Asegúrate de que no haya otras apps pesadas funcionando en segundo plano.

<!-- en: inference-is-very-slow -->
## La inferencia es muy lenta

Sin una GPU utilizable, el modelo local funciona solo en la CPU, que es
bastante más lenta (sobre todo en los primeros tokens):

- Mientras se escribe una respuesta, el estado del agente indica el backend en el
  que de verdad funciona el modelo. Si dice CPU con **GPU** elegida, la GPU no
  arrancó y esta sesión pasó a la CPU.
- **Probar backend**, en **Configuración → Modelos**, mide con qué rapidez
  genera el backend elegido.
- Prueba un modelo más pequeño desde la pantalla **Modelos**: incluso un modelo
  de 1B a 2B parámetros puede ser bastante más rápido en la CPU que uno de 7B o
  más.
- Baja **Contexto máximo** en **Configuración → Generación → Avanzado**. Un
  contexto más corto significa menos trabajo por token.

<!-- en: the-app-freezes-while-the-model-is-answering -->
## La app se congela mientras el modelo responde

En algunos teléfonos la pantalla deja de responder mientras el modelo local
prepara una respuesta en la **GPU**, y Android puede ofrecer cerrar la app. El
modelo y la pantalla comparten un mismo chip gráfico, y el modelo puede tenerlo
ocupado el tiempo suficiente para que la pantalla tenga que esperar. El motor
local tiene un modo de menor prioridad pensado para mantener la pantalla fluida,
pero una app todavía no puede activarlo
([LiteRT-LM#3992](https://github.com/google-ai-edge/LiteRT-LM/issues/3992)).

Mientras tanto, abre **Configuración → Modelos**, pon **Backend de inferencia**
en **CPU** y toca **Reiniciar** en el aviso. Las respuestas serán más lentas,
pero la pantalla ya no espera al modelo.

<!-- en: a-tool-says-it-is-unavailable -->
## Una herramienta dice que no está disponible

Hay dos causas habituales:

- Una herramienta integrada que delega en un proveedor en la nube (por ejemplo,
  **delegate_task**) necesita al menos una clave de API de un proveedor en la nube en
  **Configuración → Modelos → Proveedores externos**. Sin clave, el agente no la
  ve.
- Una herramienta de un servidor MCP necesita que el propio servidor esté
  accesible. Abre la pantalla **Herramientas** y comprueba que el servidor sigue
  en **SERVIDORES MCP**; si la URL cambió o el servidor no está en marcha, la
  herramienta fallará con un evento de error en la consola.

<!-- en: an-mcp-server-is-connected-but-a-tool-i-expect-isnt-there -->
## Un servidor MCP está conectado, pero falta una herramienta que esperaba

Si la fila del servidor dice `ok` y la herramienta sigue sin aparecer,
probablemente el servidor no se la publica a esta app. Algunas herramientas solo
funcionan con clientes que admiten funciones adicionales que Knotwork todavía no
tiene, y un servidor bien hecho las deja fuera de la lista en lugar de ofrecer
algo que fallaría. Consulta [What the tool count on a server row
means](user-guide.md#what-the-tool-count-on-a-server-row-means): es una
limitación real, no un error de configuración que puedas corregir desde la
pantalla Herramientas.

Si la herramienta aparece bajo el servidor pero marcada con *No se ofrece al
agente*, otra herramienta tiene el mismo nombre: una del dispositivo o la misma
herramienta en un servidor que aparece más arriba. Consulta [When two tools share
a name](user-guide.md#when-two-tools-share-a-name).

<!-- en: the-same-question-goes-to-the-cloud-one-time-and-stays-local-the-next -->
## La misma pregunta va a la nube una vez y se queda en el dispositivo la siguiente

Normalmente es el enrutador leyendo la conversación, no un error. Consulta [Why
the same question can take a different
route](user-guide.md#why-the-same-question-can-take-a-different-route) para saber
qué cambiar si quieres que la decisión se repita.

<!-- en: a-background-run-dies-the-moment-i-leave-the-app -->
## Una ejecución en segundo plano muere en cuanto salgo de la app

Casi siempre son las restricciones de batería, no la app: con el ajuste
predeterminado, el sistema puede cerrar el proceso a los pocos segundos de
salir de la app. Consulta [Battery settings decide whether any of this
happens](user-guide.md#battery-settings-decide-whether-any-of-this-happens). La
misma causa está detrás de la mayoría de los activadores y las tareas
programadas que nunca dan un resultado.

<!-- en: a-pipeline-went-missing -->
## Desapareció un pipeline

Si eliminas o cambias el nombre de un pipeline asignado a un chat, el chat pasa
al pipeline marcado como **principal** en la biblioteca con su siguiente mensaje.
No queda ningún estado roto (las respuestas siguen funcionando), pero la
conversación empezará a usar el pipeline que esté marcado como principal en ese
momento. Para elegir otro pipeline, abre la pantalla **Pipelines** y toca
**Usar como principal** en el que quieras, o empieza un chat nuevo.

<!-- en: the-agent-stopped-mid-run -->
## El agente se detuvo a mitad de una ejecución

Una ejecución larga puede alcanzar uno de sus **límites de ejecución**. Entonces
se pausa y pregunta: el chat dice **En pausa por un límite de seguridad**, indica
qué margen se agotó y cuánto se usó, y ofrece **Continuar (+N)** o **Detener la
ejecución**. Continuar le da una porción más del mismo margen y sigue donde se
quedó; vuelve a preguntar cuando esa porción se agota.

Si la ejecución se detuvo en vez de pausarse, el chat dice **La detuvo un límite
de seguridad**: elegiste detenerla, el plazo de espera se cerró antes de que
respondieras o la ejecución no tenía ningún registro en el que esperar (una
ejecución de prueba desde el editor). **Ajustar límites**, en ese mensaje, abre
la pantalla donde puedes subir el límite para la próxima vez.

Dos cosas conviene saber antes de subir nada. El límite en cuestión puede ser el
de **tokens** y no el de pasos, así que lee el mensaje en vez de suponerlo; y una
ejecución que no iniciaste tú se rige por los límites **en segundo plano**, que
se configuran aparte en la misma pantalla.

Pero lee primero el mensaje, porque no toda parada a mitad de una ejecución es
un límite. **Detenida: la ejecución no avanzaba** significa que la ejecución se
repetía a sí misma y se terminó por eso: subir un límite solo le daría más
vueltas, así que abre la consola y mira qué paso vuelve una y otra vez.
**Detenida: la ejecución dejó de responder** es otra cosa distinta: un paso dejó
de responder, casi siempre una herramienta o un servidor que nunca contestó, y la
ejecución se terminó para que pudieran ejecutarse otros mensajes. Esa sí puede
funcionar al segundo intento.

<!-- en: a-trigger-didnt-fire -->
## Un activador no se disparó

Abre el activador y lee su **Registro de evaluaciones** (consulta
[Triggers](user-guide.md#checking-what-a-trigger-has-been-doing)): la respuesta
casi siempre está ahí, y cuál de estas tres formas tome decide qué hacer después:

- **Hay una entrada para el momento que esperabas, y dice que no se ejecutó.**
  El motivo está escrito: la condición no se cumplía, ya se había disparado en
  ese periodo, el activador estaba desactivado o no hay ningún pipeline
  asignado. No hay nada roto: o la condición no es la que creías, o hay que
  asignarle un pipeline al activador o activarlo.
- **Hay una entrada, se disparó, pero terminó mal.** *Falló* apunta al pipeline
  (abre la ejecución en el chat del activador y lee la consola). Dos fallos se
  nombran solos: *la ejecución no avanzaba* significa que se repetía a sí misma,
  y el registro de la consola muestra el mismo paso ejecutándose una y otra vez
  antes de la línea que la termina; *la ejecución dejó de responder* significa
  que un paso dejó de responder: el registro de la consola termina con el paso
  que empezó y nunca acabó, y ese paso es lo que hay que mirar, aunque la pestaña
  **Vars** no tendrá nada de él, porque nunca produjo nada. *La detuvo el
  sistema* significa que el proceso se cerró a mitad de la ejecución, que es un
  problema de batería o de presión de memoria, no del pipeline; *Se agotó el
  tiempo de espera de la aprobación* significa que la ejecución quedó en pausa
  en una herramienta que pide aprobación y el plazo de aprobación venció antes de que
  respondieras: responde antes, amplía **Configuración → Segundo plano y
  activadores → Plazo de aprobación** o usa un pipeline cuyas herramientas no
  necesiten aprobación. Si la entrada dice *La solicitud nunca te llegó*, pasó
  lo contrario: la aprobación no se pudo entregar, así que no había ninguna
  notificación que esperar.
- **No hay ninguna entrada cerca de esa hora** y la lista muestra **Atrasado**.
  Nunca se despertó a la app para comprobarlo. Es un problema del sistema:
  excluye la app de la optimización de batería y, en teléfonos con una capa
  extra del fabricante (Samsung, Xiaomi, OnePlus y otros), sácala también de
  cualquier lista de apps «en suspensión» o «en suspensión profunda». Ten en
  cuenta que todos los activadores, salvo el de carga, funcionan con una
  programación en segundo plano que se puede aplazar (consulta [How soon a
  trigger fires](user-guide.md#how-soon-a-trigger-fires)): que una comprobación
  llegue tarde es normal; un hueco de muchas veces el propio intervalo del
  activador no lo es.

Un activador que nunca se ha evaluado muestra *Aún no hay evaluaciones*. Es lo
esperado justo después de crearlo, porque la primera comprobación depende de la
siguiente consulta programada.

<!-- en: memory-search-isnt-finding-an-obvious-entry -->
## La búsqueda en memoria no encuentra un recuerdo evidente

Si un recuerdo que sabes que existe nunca aparece en una respuesta (ni en la
búsqueda de la pantalla Memoria), recorre esta lista:

- **El nodo no lee la memoria.** Solo los nodos con la entrada **Memoria a
  largo plazo** activada toman datos del almacén. Abre el pipeline, revisa la
  sección *Datos de entrada* del nodo y activa el filtro **MEMORY** de la
  consola: si no hay una línea `Memory: query=… → N hits` para la ejecución, el
  nodo activo nunca consultó la memoria.
- **El umbral de similitud es demasiado alto.** Un **Umbral de similitud** alto
  en **Configuración → Memoria** descarta los fragmentos poco relacionados antes
  de que lleguen al prompt. Bájalo (o fija el recuerdo: los fragmentos fijados
  se saltan el umbral por completo y siempre quedan arriba).
- **Recuerdos más recientes ganaron los puestos.** La antigüedad nunca descarta
  un recuerdo: un fragmento que supera el umbral de similitud se puede recuperar
  por viejo que sea. Pero la novedad desempata, así que cuando más recuerdos
  superan el umbral de los que permite **Resultados de búsqueda (top-K)**, los
  más nuevos se quedan con los puestos. Sube top-K, sube **Vida media por
  antigüedad** (amplía el periodo en el que la novedad todavía pesa mucho) o
  fija el recuerdo.
- **Otro recuerdo decía lo mismo.** Los recuerdos cuyo significado casi coincide
  con otro mejor clasificado se funden con él, así que un duplicado con otras
  palabras no ocupa un segundo puesto. La copia que queda es la fijada, si la
  hay; si no, la mejor clasificada, y con relevancia y antigüedad parecidas, una
  que guardaste a mano va por delante de una extraída. Con el modelo de
  embeddings local esto también afecta a recuerdos sin relación escritos en
  ruso, que el modelo no distingue entre sí: consulta [Limitaciones
  conocidas](faq.md#known-limitations).
- **El recuerdo está en cola para recalcular su vector.** Un fragmento importado
  con otro proveedor de embeddings no se puede encontrar hasta que termine el
  recálculo en segundo plano (mientras tanto puntúa cerca de 0). Dale un momento
  o fuérzalo con **Configuración → Memoria → Recalcular**.
- **Cambió el proveedor.** Cambiar el **Modelo de embeddings** deja los
  fragmentos existentes en el espacio vectorial anterior; usa **Recalcular** para
  que todo el almacén vuelva a compartir el espacio del proveedor activo. La
  tarjeta Memoria muestra un aviso fijo que recomienda recalcularlos mientras
  dure esa diferencia.
- **Está activado Bloquear la red al modelo local.** Mientras lo está, la
  memoria no puede usar OpenAI como **Modelo de embeddings**, ni Ollama en una
  dirección que no sea `localhost` o una IP privada, y pasa al modelo local: así
  que los recuerdos con vectores del otro proveedor puntúan cerca de 0 hasta que
  quites la restricción. La misma restricción retira también la herramienta
  integrada `search_tool`, así que un paso que buscaba un dato en Wikipedia
  devuelve un rechazo que nombra el ajuste en lugar de un fragmento.
- **Nunca se extrajo.** La extracción automática solo guarda datos duraderos
  (preferencias, acontecimientos, relaciones) y omite las charlas sin importancia y los
  datos que ya tienes. Si cambia un dato que ya tienes, se actualiza ese recuerdo
  en vez de agregar otro, o, si el recuerdo está fijado, el cambio espera a su
  lado. Si un dato no pasó el filtro, agrégalo a mano con **Guardar en memoria**
  o con el botón **Nuevo recuerdo**.

<!-- en: your-data-cant-be-unlocked-appears-at-startup -->
## Al iniciar aparece «No se pueden desbloquear tus datos»

Todos los datos locales se guardan en una base de datos cifrada cuya clave vive
en el almacén de claves por hardware de Android. En raras ocasiones (normalmente
justo después de una actualización del sistema, o por un fallo pasajero del
almacén de claves) esa clave puede quedar ilegible por un tiempo, y la app
muestra una pantalla de recuperación en lugar de iniciarse:

- **Toca primero Reintentar, quizá más de una vez.** Los fallos del almacén de
  claves suelen ser pasajeros; si la clave vuelve a poder leerse, la app abre tus
  datos intactos. En algunos casos ayuda reiniciar el dispositivo antes de volver
  a intentarlo.
- **Borrar datos es el último recurso.** Si reintentar nunca pasa de esa
  pantalla, la clave se perdió para siempre y nadie puede volver a abrir la base
  de datos cifrada, ni siquiera la propia app. **Borrar datos** elimina la base
  de datos, los archivos de la carpeta de trabajo del agente, los adjuntos y las
  tareas programadas, y genera una clave nueva para que empieces de cero. La
  configuración y las claves guardadas se conservan, y también los modelos
  descargados: vuelven a aparecer en Modelos, sin activar, así que elige uno
  antes de chatear. La acción es irreversible y exige escribir una confirmación.

En este estado, la app nunca elimina tus datos ni cambia su clave por su cuenta:
sin la clave original, el contenido de la base de datos no se puede recuperar,
así que la decisión de borrarlo es siempre tuya.

<!-- en: installing-over-a-copy-from-google-play-fails -->
## Falla la instalación sobre una copia de Google Play

Instalar un APK de una versión publicada en GitHub, o Knotwork desde F-Droid, en
un dispositivo que ya la tiene de Google Play termina con un error que dice que
la app no se instaló o que el paquete entra en conflicto con uno existente; el
texto depende de la versión de Android y de la app que hace la instalación.
También falla al revés: una copia instalada desde un APK publicado o desde
F-Droid no se puede actualizar desde Google Play.

Ninguno de los dos archivos está dañado. Google Play firma la app con una clave
distinta de la de las versiones de GitHub y de F-Droid ([Signing identities by
channel](release.md#signing-identities-by-channel)), y Android solo actualiza una
app con una copia del mismo firmante. Para cambiar de canal:

1. Haz una copia de seguridad: Configuración → Privacidad → **Copia de seguridad
   y restauración** → **Crear copia de seguridad…**
   ([cómo](user-guide.md#backup--restore)). Marca **Incluir claves de API y
   credenciales** para llevarte tus claves, y guarda el archivo donde no llegue
   una desinstalación: Descargas, una tarjeta de memoria o un almacenamiento en
   la nube.
2. Desinstala la app. Eso elimina todo lo que guarda en el dispositivo,
   incluidos los modelos descargados.
3. Instala desde el otro canal, abre **Copia de seguridad y restauración** →
   **Restaurar desde archivo…**, elige la copia, escribe su contraseña y
   confirma. Cuando la app se reinicie, vuelve a descargar los modelos desde la lista que muestra.

La versión que instales tiene que poder leer la copia. Una copia de una versión
más nueva se restaura salvo que esa versión haya cambiado el formato de la copia
o la base de datos; entonces la restauración se niega y te pide actualizar
primero. Google Play puede ir por detrás de la página de versiones mientras una
actualización está en revisión, y F-Droid hasta que se ejecute su siguiente compilación, así que antes de desinstalar comprueba que el otro canal ofrece la
misma versión o una posterior.

<!-- en: a-model-download-paused-after-five-hours -->
## La descarga de un modelo se pausó tras cinco horas

Android deja que una app transfiera datos en segundo plano seis horas al día, y
detiene la app si una transferencia dura más. Por eso la descarga de un modelo
se pausa sola tras cinco horas descargando. Muestra una notificación de que se pausó (si la app tiene permiso para enviar notificaciones), y la pantalla Modelos lo indica
la próxima vez que la abras mientras no haya otra descarga en curso, con un
botón **Continuar**; o bien el paso de descarga de la configuración inicial, o
la página del modelo en Explorar, si llegas antes a una de ellas. No se pierde
nada: vuelve a iniciar la misma descarga y continúa donde se quedó.

<!-- en: a-downloaded-model-did-not-match-its-checksum -->
## Un modelo descargado no coincidió con su suma de comprobación

Hugging Face publica un SHA-256 de los archivos de modelos que aloja, y la app
compara con él el archivo descargado antes de instalar el modelo. Si no
coinciden, el archivo se elimina, no se instala ningún modelo y la app lo avisa
igual que con una descarga pausada.

Una forma en que pasa: el archivo cambió en Hugging Face mientras tu descarga
estaba interrumpida, así que la parte descargada antes y la descargada después
pertenecen a dos versiones del archivo. Vuelve a iniciar la descarga: obtiene la
versión actual desde el principio.

Si una descarga nueva falla igual, el archivo que llega a tu teléfono no es el
que publica Hugging Face. Prueba con otra red y, si sigue pasando, abre un issue
indicando el modelo y el archivo.


---

<!-- en: see-also -->
## Véase también

- [`faq.md`](faq.md): qué se admite, qué no y dónde está cada cosa.
- [`user-guide.md`](user-guide.md): cómo funciona cada pantalla y cada función.
- [`../SECURITY.md`](../SECURITY.md): política de seguridad, modelo de amenazas y
  qué recopilan los informes de fallos.
