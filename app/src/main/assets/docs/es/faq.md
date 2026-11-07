<!-- translation of docs/faq.md, sha256 9014b36abf2b6d1e57c85e163f530c2b14450f44eaa327e0997a59b389ccb4c6 -->
<!-- en: frequently-asked-questions -->
# Preguntas frecuentes

Respuestas cortas a las preguntas que de verdad aparecen. Cada una enlaza a la
página que trata el tema completo.

¿Qué página necesitas?

- **Esta** responde a «¿puede…?, ¿hace…?, ¿dónde está…?, ¿qué pasa si…?».
- [`troubleshooting.md`](troubleshooting.md) responde a «algo no funciona».
- [`user-guide.md`](user-guide.md) describe cómo funciona cada pantalla y cada
  función, y es la descripción de referencia del comportamiento de la app.

La guía del usuario y los demás documentos enlazados desde aquí, salvo
«Solución de problemas», se abren en el navegador y están en inglés. Sus
secciones se citan con el nombre del original.

Si buscas el camino más corto desde una instalación nueva hasta algo útil, son
[los cinco pasos de abajo](#im-installed-now-what-do-i-actually-press).

<!-- en: contents -->
## Contenido

- [Instalar y elegir un modelo](#installing-and-choosing-a-model)
- [Qué sale del dispositivo y qué no](#what-leaves-the-device-and-what-does-not)
- [Pipelines y el editor](#pipelines-and-the-editor)
- [Activadores y trabajo en segundo plano](#triggers-and-background-work)
- [Herramientas, MCP y confirmaciones](#tools-mcp-and-confirmations)
- [Automatización externa (Tasker, MacroDroid, adb)](#external-automation-tasker-macrodroid-adb)
- [Límites de ejecución y cómo detener una ejecución](#run-limits-and-stopping-a-run)
- [Memoria](#memory)
- [Limitaciones conocidas](#known-limitations)

---

<!-- en: installing-and-choosing-a-model -->
## Instalar y elegir un modelo

<!-- en: what-do-i-need-to-run-this -->
### ¿Qué necesito para usarla?

Android 14 o posterior y unos 2 GB de RAM libre para el modelo. Lo que limita es
la memoria; un teléfono sin una GPU utilizable también funciona, solo que más
lento. La lista completa está en [README § Requirements](../README.md#requirements).

<!-- en: do-i-have-to-download-a-model-at-all -->
### ¿Tengo que descargar un modelo sí o sí?

Para respuestas en el dispositivo, sí: el modelo es un archivo que vive en tu
teléfono. La alternativa es un proveedor en la nube con tu propia clave de API,
que no necesita descarga. Hace falta una de las dos cosas para que algo
responda, y una instalación nueva no tiene ninguna. Consulta [Getting
started](user-guide.md#getting-started).

<!-- en: which-model-should-i-start-with -->
### ¿Con qué modelo empiezo?

La configuración inicial lo elige por ti: cada escenario indica el modelo que
necesita y descarga exactamente ese. Si eliges a mano, los dos modelos de la lista son **Gemma 4 E2B** y **Gemma 4 E4B**; elige E2B si tienes poco almacenamiento o poca RAM. Consulta [Getting started](user-guide.md#getting-started).

<!-- en: which-cloud-providers-are-supported -->
### ¿Qué proveedores en la nube se admiten?

En la nube: OpenAI, Anthropic, Google Gemini, DeepSeek, OpenRouter y Groq, cada
uno con tu propia clave. Tu propio servidor: **Ollama** o cualquier servidor
compatible con la API de OpenAI. Todos se usan solo si tú los activas. Están en
**Configuración → Modelos → Proveedores externos**, y **Probar la conexión**, en
la pantalla de cada proveedor, comprueba la clave y la dirección sin enviar
ningún prompt.

<!-- en: can-i-point-it-at-my-own-openai-compatible-endpoint -->
### ¿Puedo conectarla a mi propio endpoint compatible con OpenAI?

Sí: agrega un **Servidor compatible con OpenAI** y escribe su dirección tal como
la indica la documentación del servidor, incluido `/v1` (vLLM, LM Studio y
llama.cpp ofrecen la API ahí). La clave es opcional; sin clave no se envía
ningún encabezado `Authorization`. Elige el modelo cuando **Probar la conexión**
traiga la lista del servidor, o escribe su ID.

<!-- en: my-ollama-server-is-not-on-my-home-network-will-it-work -->
### Mi servidor Ollama no está en mi red de casa. ¿Funcionará?

Por `https`, sí: la fila de Ollama en **Configuración → Modelos → Proveedores
externos** guarda su URL base. Por `http` sin cifrar se rechaza, salvo que la
dirección esté en una red privada o hayas aprobado ese origen en concreto,
porque la app no envía tráfico sin cifrar por internet abierto.

<!-- en: what-is-custom-model-url-and-can-it-use-authentication -->
### ¿Qué es «URL de un modelo propio» y puede usar autenticación?

Descarga un **archivo** `.litertlm` al teléfono desde un enlace directo; no es
una forma de conectar la app a un modelo remoto que se queda en un servidor.
Puede autenticarse, pero solo con un token Bearer y solo en `huggingface.co`:
completa el campo del token de acceso de Hugging Face en la pantalla Modelos y
una descarga desde ese host lo lleva como encabezado `Authorization`; un enlace
a cualquier otro host se descarga sin él. No hay flujo OAuth ni ningún otro
esquema de autenticación. Consulta [Download a model](user-guide.md#2-download-a-model).

<!-- en: does-the-app-check-a-downloaded-model -->
### ¿La app comprueba un modelo descargado?

Cuando Hugging Face indica el SHA-256 del archivo en su respuesta a la descarga
(lo hace con los archivos grandes que guarda en LFS, como suelen ser los de
modelos), sí: un archivo que no coincide se elimina en lugar de instalarse. Un
enlace a cualquier otro host no indica ningún hash, así que su archivo se
instala sin comprobar. La pantalla Modelos marca cada modelo como
**Verificado** o **Sin verificar**. La comprobación demuestra que el archivo es
el que publica Hugging Face, no que su autor sea de confianza. Consulta
[Download a model](user-guide.md#2-download-a-model).

<!-- en: can-i-browse-and-install-models-from-hugging-face -->
### ¿Puedo explorar e instalar modelos de Hugging Face?

Sí, incluidos los repositorios con acceso restringido, una vez que guardes un
token de acceso. Consulta [Discovering models from Hugging
Face](user-guide.md#discovering-models-from-hugging-face).

<!-- en: what-is-the-foss-build-and-how-does-it-differ -->
### ¿Qué es la versión FOSS y en qué se diferencia?

Es la variante que distribuye F-Droid: sin informes de fallos, sin analítica y
sin el interruptor de consentimiento para ellos. Todo lo demás (modelos locales,
proveedores en la nube, MCP, activadores) es idéntico. Consulta [README §
Install](../README.md#install).

---

<!-- en: what-leaves-the-device-and-what-does-not -->
## Qué sale del dispositivo y qué no

<!-- en: does-a-normal-conversation-leave-my-phone -->
### ¿Una conversación normal sale de mi teléfono?

No. La inferencia en el dispositivo significa que el mensaje, la respuesta y la
ejecución entre ambos se quedan en el teléfono. No hay cuenta, ni inicio de
sesión, ni ningún servidor del mantenedor. Consulta [PRIVACY § What never leaves
your device](../PRIVACY.md#4-what-never-leaves-your-device).

<!-- en: so-what-can-go-out -->
### Entonces, ¿qué *puede* salir?

Seis vías. Cinco las activas tú: un **modelo en la nube** con tu propia clave
(en un nodo Nube, mediante la herramienta `delegate_task` o como **Modelo de
embeddings** de la memoria), un **servidor MCP** que agregaste, la **descarga de
un modelo**, una **llamada a la herramienta `http_request`** hacia un host que
pusiste en la lista de permitidos y los **informes de fallos**, si diste tu
consentimiento.

La sexta está activa desde el primer inicio y es la que conviene conocer: la
herramienta integrada **`search_tool`** busca un tema en Wikipedia, y el
pipeline con el que empiezas la llama para las preguntas que considera factuales.
El término de búsqueda lo escribe el modelo, así que puede llevar palabras de tu
conversación, y la herramienta es de solo lectura, así que no pregunta antes.
Desactívala con su interruptor en la pantalla **Herramientas**, o con
**Bloquear la red al modelo local**, que la retira junto con las vías a la nube.

Nada más tiene salida, aparte de los archivos que exportas tú, como un chat, un
registro, la traza de una ejecución o una copia de seguridad, que van adonde los
envíes. Consulta [PRIVACY § What can leave your
device](../PRIVACY.md#3-what-can-leave-your-device), y [SECURITY § Outbound HTTP
and the exfiltration chain](../SECURITY.md#outbound-http-and-the-exfiltration-chain)
para las restricciones en capas de la vía de la herramienta.

<!-- en: where-are-my-api-keys-stored -->
### ¿Dónde se guardan mis claves de API?

En un almacén cifrado, sellado con una clave de Android Keystore que no se puede
exportar: nunca en la configuración sin cifrar, nunca en los pipelines
exportados, nunca en los registros, y en una copia de seguridad solo si marcas
**Incluir claves de API y credenciales**. Lo mismo vale para las credenciales de
MCP y el token de Hugging Face. Consulta [SECURITY § API keys for cloud
providers](../SECURITY.md#api-keys-for-cloud-providers).

<!-- en: can-i-back-up-everything -->
### ¿Puedo hacer una copia de seguridad de todo?

Sí, en un solo archivo protegido con una contraseña que eliges tú: Configuración
→ Privacidad → **Copia de seguridad y restauración** → **Crear copia de
seguridad…**. Incluye chats, memoria, pipelines, historial de ejecuciones,
activadores, configuración y archivos; no los archivos de los modelos, ni tus
claves de API salvo que marques la casilla (un token escrito dentro de la
dirección de un servidor viaja con la dirección). El archivo va solo adonde lo
guardes. **Restaurar desde archivo…** lo devuelve, en este teléfono o en uno
nuevo, y reemplaza los datos de la app allí; los archivos de los modelos se
quedan, y también las claves propias del teléfono, salvo las que trae la copia.
Consulta [Backup & restore](user-guide.md#backup--restore).

<!-- en: is-there-telemetry -->
### ¿Hay telemetría?

Hay estadísticas de uso, se quedan en el dispositivo y nunca se transmiten: una
comprobación al compilar hace fallar la compilación si alguna dependencia de red
llega a ese código. Los informes de fallos son aparte, están desactivados por
defecto, existen solo en la versión `full` y preguntan antes de recopilar nada.
La biblioteca local detrás de la búsqueda en memoria (MediaPipe) trae su propio
informador de uso; las versiones de lanzamiento lo quitan, y solo lo conserva
una versión de depuración compilada desde el código fuente. Consulta [SECURITY §
What is collected](../SECURITY.md#what-is-collected-crash-reporting).

<!-- en: is-my-chat-history-encrypted-on-the-phone -->
### ¿Mi historial de chats está cifrado en el teléfono?

Sí: los chats, la memoria a largo plazo y las trazas de las ejecuciones viven en
una base de datos cifrada con SQLCipher. Consulta [SECURITY § Local
database](../SECURITY.md#local-database-at-rest-encryption).

---

<!-- en: pipelines-and-the-editor -->
## Pipelines y el editor

<!-- en: why-is-everything-a-pipeline-instead-of-just-a-chat-box -->
### ¿Por qué todo es un pipeline y no simplemente un chat?

Porque la gracia de la app es que puedas ver y cambiar lo que pasa entre tu
mensaje y la respuesta: qué modelo, qué herramientas, qué se confirma. Desde el
primer inicio hay un pipeline listo y activo, así que puedes ignorar todo esto
hasta que quieras cambiar algo. Consulta [Pipelines](user-guide.md#pipeline-library).

<!-- en: im-installed-now-what-do-i-actually-press -->
### Ya está instalada. ¿Qué toco ahora?

Cinco pasos, de principio a fin:

1. **Elige un escenario en la configuración inicial**: instala un pipeline que
   funciona *y* descarga el modelo que ese escenario necesita.
2. **Chat → escribe un mensaje** cuando el modelo haya terminado de descargarse. Ese
   es todo el ciclo; lo demás es opcional. (¿Elegiste *Empezar desde cero*?
   Abre **Modelos**, descarga un modelo de la lista y toca **Activar** primero:
   [cómo](user-guide.md#2-download-a-model).)
3. **Pipelines → abre el marcado como PRINCIPAL** para ver el grafo que produjo
   esa respuesta ([cómo](user-guide.md#visual-editor)).
4. **Herramientas**, para activar una herramienta o agregar un servidor MCP
   ([cómo](user-guide.md#adding-an-mcp-server)).
5. **Más → Automatización → Activadores**, para que un pipeline se ejecute solo
   ([cómo](user-guide.md#creating-or-editing-a-trigger)).

<!-- en: settings-has-a-run-limits--structured-output-section--where-are-the-actual-pipelines -->
### Configuración tiene una sección «Límites de ejecución y salida estructurada». ¿Dónde están los pipelines de verdad?

En **Pipelines**, en la biblioteca. Esa categoría de la configuración guarda los
límites y las salvaguardas que se aplican a las ejecuciones, no los pipelines;
antes tenía un nombre que prometía otra cosa. Consulta [Pipeline
library](user-guide.md#pipeline-library).

<!-- en: what-does-this-node-actually-do -->
### ¿Qué hace realmente este nodo?

Cada tipo de nodo se describe en el [recetario de pipelines](cookbook.md), junto
con lo que hace con su entrada, cuáles de sus campos de configuración cambian una
ejecución y cuáles se guardan pero se ignoran, y cinco recetas para importar.

<!-- en: can-i-edit-a-pipeline-on-a-computer -->
### ¿Puedo editar un pipeline fuera del teléfono?

Sí: hay un editor independiente para el navegador que lee y escribe el mismo
JSON, así que puedes construir con un teclado de verdad e importar el archivo.
Consulta [Browser pipeline editor](user-guide.md#browser-pipeline-editor).

<!-- en: can-i-share-a-pipeline-with-someone-else -->
### ¿Puedo compartir un pipeline con otra persona?

Puedes exportar uno (o una composición entera como paquete) y la otra persona lo
importa. Ten en cuenta que el formato de archivo **no es un contrato de
compatibilidad antes de la versión 1.0**: una importación te dice por su nombre
lo que no pudo leer, y eso no es lo mismo que no perderlo. Conserva el original.
Consulta [Sharing pipeline
files](user-guide.md#sharing-pipeline-files-what-compatibility-you-can-count-on).

---

<!-- en: triggers-and-background-work -->
## Activadores y trabajo en segundo plano

<!-- en: where-are-the-triggers -->
### ¿Dónde están los activadores?

**Más → Automatización → Activadores**: la primera fila de la primera sección.
Consulta [Triggers](user-guide.md#triggers) y [More tab](user-guide.md#more-tab).

<!-- en: does-the-agent-run-when-the-app-is-closed -->
### ¿El agente funciona con la app cerrada?

Sí: los activadores, las tareas programadas y las ejecuciones ya en curso siguen
con la app cerrada, y una confirmación que te toca dar llega como notificación.
Esto depende por completo del ajuste de batería de abajo, que no viene activado
por defecto. Consulta [What happens when you close the app during a
run](user-guide.md#what-happens-when-you-close-the-app-during-a-run).

<!-- en: why-does-nothing-happen-in-the-background-on-my-phone -->
### ¿Por qué en mi teléfono no pasa nada en segundo plano?

Casi siempre es el ajuste de batería, que la app no puede concederse a sí misma.
[Battery settings decide whether any of this
happens](user-guide.md#battery-settings-decide-whether-any-of-this-happens) es la
explicación de referencia; [Una ejecución en segundo plano muere en cuanto salgo
de la app](troubleshooting.md#a-background-run-dies-the-moment-i-leave-the-app) y
[Un activador no se disparó](troubleshooting.md#a-trigger-didnt-fire) son los dos
fallos concretos.

<!-- en: how-soon-after-the-condition-does-a-trigger-actually-fire -->
### ¿Cuánto tarda un activador en dispararse después de que se cumple la condición?

No es inmediato. Todos los activadores, salvo el de carga, funcionan con una
programación en segundo plano que se puede aplazar, así que un retraso es normal;
un hueco de muchas veces el propio intervalo del activador no lo es. Consulta
[How soon a trigger fires](user-guide.md#how-soon-a-trigger-fires).

<!-- en: does-the-model-stay-in-memory-between-runs -->
### ¿El modelo se queda en memoria entre ejecuciones?

No: se descarga de la memoria tras cinco minutos sin uso, y también cuando el sistema pide que
le devuelvan memoria (salvo que haya una generación en curso, que nunca se
interrumpe por eso). Así no ocupa RAM ni gasta batería mientras no se ejecuta
nada.

<!-- en: how-do-i-get-the-background-journal-off-my-phone -->
### ¿Cómo saco del teléfono el registro de lo que pasó en segundo plano?

Hay dos acciones en la barra superior de Activadores: **compartir** el registro
de evaluaciones como un archivo JSON o **guardarlo** en una carpeta que elijas.
Incluye todos los activadores, no solo el que estabas viendo, porque la pregunta
útil suele ser sobre un hueco y no sobre una fila. El registro de solicitudes en
Configuración → Segundo plano y activadores → Automatización externa tiene las
mismas dos acciones para las solicitudes entrantes.

Adjunta cualquiera de los dos a un reporte de error sobre la fiabilidad en
segundo plano: muestra lo que hizo la app mientras no mirabas. El archivo
contiene las filas del registro: el mensaje de error de una ejecución fallida,
pero ningún mensaje que recibió una ejecución ni ninguna respuesta que produjo.
Ninguna de las dos acciones usa la red. Consulta [Checking what a trigger has
been doing](user-guide.md#checking-what-a-trigger-has-been-doing).

---

<!-- en: tools-mcp-and-confirmations -->
## Herramientas, MCP y confirmaciones

<!-- en: what-address-does-my-mcp-server-need -->
### ¿Qué dirección necesita mi servidor MCP?

El endpoint que tu servidor muestra al arrancar, con la ruta incluida; el ejemplo
del propio formulario es `https://mcp.example.com/mcp`. No hay un campo aparte
para el puerto: un puerto que no sea el predeterminado va en la URL, y el
selector de transporte debajo del campo tiene que coincidir con el endpoint:
**Streamable HTTP**, que viene elegido para un servidor nuevo, o **SSE** para un
servidor que solo habla el transporte antiguo. Consulta [Adding an MCP
server](user-guide.md#adding-an-mcp-server).

<!-- en: which-authentication-methods-do-mcp-servers-support -->
### ¿Qué métodos de autenticación admiten los servidores MCP?

Token Bearer, HTTP Basic o un encabezado propio con una clave de API; lo que
elijas se guarda cifrado, por servidor. **OAuth no se admite.** Consulta
[Limitaciones conocidas](#known-limitations).

<!-- en: is-there-a-test-connection-button -->
### ¿Hay un botón para probar la conexión?

Sí. **Probar la conexión**, en el formulario del servidor, se conecta con los
valores que escribiste, obtiene la lista de herramientas del servidor y se
desconecta, todo antes de guardar nada. Responde con el número de herramientas o
con lo que salió mal. Consulta [Adding an MCP server](user-guide.md#adding-an-mcp-server).

<!-- en: my-server-publishes-16-tools-but-the-app-shows-13 -->
### Mi servidor publica 16 herramientas, pero la app muestra 13.

Eso lo decide el servidor, no la app: consulta [Un servidor MCP está conectado,
pero falta una herramienta que
esperaba](troubleshooting.md#an-mcp-server-is-connected-but-a-tool-i-expect-isnt-there),
que explica por qué y qué significa para la herramienta que falta.

<!-- en: why-does-it-ask-me-before-every-tool-call -->
### ¿Por qué me pregunta antes de cada llamada a una herramienta?

Porque el nivel de riesgo de la herramienta es *Cuidado* o *Peligro*, y esa barrera se aplica donde se ejecuta la herramienta, no se le sugiere al modelo.
Dos ajustes la amplían o la endurecen para todo a la vez: **Pedir aprobación** puede ponerse en *Todas* para preguntar por *cada* llamada, y **Bloquear herramientas peligrosas**
rechaza directamente las peligrosas en vez de pedir aprobación. Los dos están en
**Configuración → Herramientas y carpeta de trabajo**.

Para que deje de preguntar por **una** herramienta, ábrela desde la pantalla
**Herramientas** y pon su **Nivel de riesgo** en *Lectura*. Funciona con las
herramientas MCP y con las que se descubren en el dispositivo, que empiezan en
*Cuidado* precisamente porque la app no puede saber qué hacen; una herramienta
integrada en la app muestra su nivel en lugar de dejar cambiarlo, porque la
barrera lo obtiene del código. Consulta [Risk levels and
human-in-the-loop](user-guide.md#risk-levels-and-human-in-the-loop).

<!-- en: can-other-apps-on-my-phone-expose-tools-to-the-agent -->
### ¿Otras apps de mi teléfono pueden ofrecerle herramientas al agente?

Android deja que cualquier app publique AppFunctions, pero ejecutar las
AppFunctions de otra app requiere un permiso (`EXECUTE_APP_FUNCTIONS`) que
Android 16 solo da a apps privilegiadas del sistema, y una app que instalas tú no
lo es. Así que lo que tienes es el catálogo integrado más lo que conectes por
MCP. Consulta [Built-in tools](user-guide.md#built-in-tools).

---

<!-- en: external-automation-tasker-macrodroid-adb -->
## Automatización externa (Tasker, MacroDroid, adb)

<!-- en: can-tasker-start-a-run -->
### ¿Tasker puede iniciar una ejecución?

Sí: es un contrato admitido, con ejemplos completos para Tasker, MacroDroid y
`adb`. Consulta [external-automation.md](external-automation.md).

<!-- en: does-it-work-out-of-the-box -->
### ¿Funciona sin configurar nada?

No, a propósito. El punto de entrada está **desactivado por defecto**, e incluso
activado no hace nada hasta que asignes exactamente un pipeline que las apps
externas pueden ejecutar. Una solicitud que nombre cualquier otra cosa se
rechaza; nunca se redirige. Consulta [Switching it
on](external-automation.md#switching-it-on).

<!-- en: how-do-i-get-the-result-back -->
### ¿Cómo recibo el resultado?

La solicitud puede indicar un broadcast de respuesta, que lleva el estado de la
ejecución, nunca su salida: haz que el pipeline deje el resultado en algún lugar
que puedas leer. Los estados y los motivos de rechazo están enumerados. Consulta
[Receiving the callback](external-automation.md#receiving-the-callback).

<!-- en: does-calling-from-outside-skip-the-confirmations -->
### ¿Llamar desde fuera se salta las confirmaciones?

No. Una llamada externa no es una forma de aprobación: la confirmación por una
persona y el bloqueo de herramientas peligrosas se aplican exactamente igual que
en las ejecuciones en segundo plano de la propia app, y la aprobación llega como
notificación. Consulta [The safety
model](external-automation.md#the-safety-model-in-one-paragraph).

---

<!-- en: run-limits-and-stopping-a-run -->
## Límites de ejecución y cómo detener una ejecución

<!-- en: what-stops-a-runaway-pipeline -->
### ¿Qué detiene un pipeline desbocado?

Cuatro números y un detector de repeticiones. Pasos por ejecución y tokens por
ejecución, cada uno con un margen aparte para las ejecuciones que no iniciaste
tú; y, por separado, una vigilancia de la ejecución que repite el mismo paso con
la misma entrada, que primero la avisa y después la termina. Consulta [Run
limits](user-guide.md#run-limits) y [When a run goes in
circles](user-guide.md#when-a-run-goes-in-circles).

<!-- en: a-run-hit-its-limit-is-the-work-lost -->
### Una ejecución llegó a su límite. ¿Se perdió el trabajo?

No: te está esperando. Alcanzar un límite pausa la ejecución en vez de
terminarla, y el chat ofrece **Continuar (+N)** o **Detener la ejecución**.
Continuar le da una porción más del mismo margen y la reanuda desde donde se
detuvo, así que no se repite nada. La pausa queda registrada, así que sobrevive
al cierre de la app y sigue ahí cuando vuelves. Consulta [When a run reaches a
limit](user-guide.md#when-a-run-reaches-a-limit).

<!-- en: does-continuing-past-a-limit-turn-the-limit-off -->
### ¿Continuar después de un límite lo desactiva?

No. Te da **una porción más**, el mismo margen otra vez, que es el número que
muestra el botón, y la ejecución vuelve a preguntar cuando se agota.

<!-- en: can-i-cap-what-a-run-costs-me -->
### ¿Puedo limitar lo que me cuesta una ejecución?

No. La app funciona con tu propia clave de API, nunca ve tu factura y no te va a
mostrar una cifra que tendría que adivinar. El límite de tokens es el control
más cercano.

<!-- en: how-do-i-stop-a-run-that-is-already-going -->
### ¿Cómo detengo una ejecución que ya está en marcha?

Toca **detener** junto al cuadro de mensaje del chat. Termina la ejecución, no solo lo que
muestra la pantalla: el trabajo se detiene y la conversación recibe una línea que
dice que la ejecución se detuvo antes de poder responder. Un mensaje que aún
espera detrás de otra ejecución se cancela igual. Cambiar a otro chat **no**
detiene nada: la ejecución sigue, y por eso puedes volver a ella.

Hay dos controles más que terminan una ejecución: **Detener la ejecución** en la
pausa por un límite y, para una cadena de tareas que el agente se programó a sí
mismo, **Más → Tareas → Detener todas las tareas programadas**, que
también cancela una que se esté ejecutando.

<!-- en: my-run-stopped-by-itself-which-limit-was-it -->
### Mi ejecución se detuvo sola. ¿Qué límite fue?

El mensaje lo dice, y no toda parada a mitad de una ejecución es un límite:
existen tres finales distintos, con soluciones distintas. Consulta [El agente se
detuvo a mitad de una ejecución](troubleshooting.md#the-agent-stopped-mid-run).

---

<!-- en: memory -->
## Memoria

<!-- en: what-does-the-agent-remember-and-when -->
### ¿Qué recuerda el agente y cuándo?

Datos duraderos que extrajo de las conversaciones (preferencias, acontecimientos, relaciones), que se recuperan por su significado cuando un nodo está configurado
para leer la memoria. Las charlas sin importancia y los datos que ya tiene se
omiten; si un dato cambia, se actualiza el recuerdo y el texto anterior queda en
su historial. Consulta [What the agent recalls, and
when](user-guide.md#what-the-agent-recalls-and-when).

<!-- en: can-i-add-or-delete-a-memory-myself -->
### ¿Puedo agregar o eliminar un recuerdo por mi cuenta?

Sí, las dos cosas, a mano; además está **Compactar**, que fusiona los recuerdos
redundantes y comprueba antes de eliminar. Consulta [Browsing long-term
memory](user-guide.md#browsing-long-term-memory).

<!-- en: does-memory-move-to-a-new-phone -->
### ¿La memoria se pasa a un teléfono nuevo?

Junto con todo lo demás, en una copia de seguridad que restauras allí:
Configuración → Privacidad → **Copia de seguridad y restauración**. La memoria
sola también se mueve exportándola e importándola, y lo exportado es un archivo
que guardas tú. Consulta [Backup & restore](user-guide.md#backup--restore) y
[Moving memory to another
device](user-guide.md#moving-memory-to-another-device-export--import).

<!-- en: why-doesnt-a-memory-i-know-exists-come-back -->
### ¿Por qué no aparece un recuerdo que sé que existe?

Hay varias razones distintas, y cuál sea decide la solución. Consulta [La
búsqueda en memoria no encuentra un recuerdo
evidente](troubleshooting.md#memory-search-isnt-finding-an-obvious-entry).

---

<!-- en: known-limitations -->
## Limitaciones conocidas

Lo que la app no hace hoy. Cada punto dice qué lo cambiaría.

- **Sin OAuth en ninguna parte.** Ni para servidores MCP ni para
  descargas de modelos. Existen los encabezados Bearer, Basic y de clave de API.
  Se revisará con el primer reporte externo de un servidor real al que no se
  pueda acceder de otra forma.
- **El nivel de riesgo de una herramienta integrada no se puede cambiar.** Las
  herramientas MCP y las que se descubren en el dispositivo tienen ahora el
  control **Nivel de riesgo** en su página de detalle; una herramienta escrita en
  la app no lo tiene, porque su nivel se obtiene del código antes de leer
  cualquier ajuste tuyo. Se revisará si la clasificación de una integrada resulta
  equivocada para un pipeline real y no solo en teoría.
- **Una imagen por mensaje.** Adjuntar una segunda reemplaza la primera, y la app
  lo avisa cuando pasa. Los collages y las comparaciones de varias imágenes
  quedan fuera.
- **Las funciones de cliente MCP quedan pendientes**: `roots`,
  `sampling` y `elicitation` no están implementadas, y por eso un servidor puede
  publicarle a esta app menos herramientas de las que tiene. Cada una es una
  superficie nueva con su propio modelo de riesgo, no algo que se agregue rápido. Se
  revisará con el primer reporte externo de una herramienta que falte en un
  servidor real; `elicitation` iría primero, porque es la única que reutiliza el
  camino de confirmación que ya existe.
- **No se recogen herramientas de otras apps.** Publicar AppFunctions está al
  alcance de cualquier app; llamar a una requiere un permiso que Android reserva
  a las apps privilegiadas del sistema, y nada en el plan cambia eso para una
  instalación normal.
- **La búsqueda en memoria funciona en inglés.** El modelo de embeddings local
  ve datos sin relación escritos en ruso como casi copias unos de otros, así que
  una búsqueda en memoria conserva uno de ellos por ejecución y descarta el resto
  como duplicados. Los datos fijados nunca se descartan así. Otros idiomas, el
  español incluido, no se han medido. OpenAI como **Modelo de embeddings** es
  multilingüe según su fabricante (no se ha medido aquí) y envía a OpenAI el
  texto de tus recuerdos. Cambiar el modelo local significa incluir uno
  multilingüe, y eso no está decidido.
- **Los archivos de pipeline no son un contrato de compatibilidad antes de la
  1.0.** La marca de versión es una señal, no una promesa, y no existe ninguna
  migración al importar. Consulta [README § Pre-release
  notice](../README.md#pre-release-notice).
- **Las cifras de fiabilidad son de un dispositivo y una persona.** Un pipeline
  programado funcionó 7,14 días sin abrir la app y completó 55 de 55 disparos sin ningún disparo perdido sin explicación. Es una medición real, y no es una validación externa.
- **No hay un modo sencillo, ni lo habrá.** La app está hecha para quien quiere
  ver y cambiar la maquinaria. Un asistente que responde preguntas ya viene con
  el sistema operativo, y la app no intenta competir con él. Consulta [README §
  Who it's for](../README.md#who-its-for).
- **Un solo mantenedor, sin empresa.** Los tiempos de respuesta se derivan de
  eso.

---

<!-- en: see-also -->
## Véase también

- [`user-guide.md`](user-guide.md): cómo funciona cada pantalla y cada función.
- [`cookbook.md`](cookbook.md): qué hace cada tipo de nodo y recetas para importar.
- [`troubleshooting.md`](troubleshooting.md): qué hacer cuando algo falla.
- [`external-automation.md`](external-automation.md): el contrato al que llaman
  otras apps.
- [`../PRIVACY.md`](../PRIVACY.md): qué se guarda y qué puede salir.
- [`../SECURITY.md`](../SECURITY.md): modelo de amenazas y cómo reportar
  vulnerabilidades.
