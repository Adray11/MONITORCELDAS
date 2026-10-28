# MonitorCeldas

App Android para **drive test**. Reúne en una sola herramienta lo que hacían NetMonitor (parámetros de celda + GPS a 1 s) y Ookla Speedtest (throughput, latencia y jitter). Genera directamente el CSV estandarizado del informe.

## Descargar el APK

Cada cambio en `main` compila el APK automáticamente (GitHub Actions):

- **Desde el celular:** pestaña **Releases** del repositorio → `apk-latest` → `MonitorCeldas.apk`.
- **También:** pestaña **Actions** → última ejecución → artefacto `MonitorCeldas-apk`.

Para instalarlo hay que permitir "instalar apps desconocidas" para el navegador o el administrador de archivos. Las versiones nuevas se instalan encima de la anterior porque están firmadas siempre con la misma llave.

Requiere **Android 10 o superior**.

## Uso en campo

1. Abre la app y acepta los permisos: ubicación precisa, teléfono y notificaciones.
2. Pulsa **Batería sin límites**. En Samsung revisa también *Ajustes → Batería → Límites de uso en segundo plano*.
3. **Apaga el WiFi** y activa el GPS.
4. Pulsa **INICIAR REGISTRO**. Funciona con la pantalla apagada; verás una notificación permanente.
5. Durante el recorrido:
   - **Tramo:** selecciona el tramo en el que estás (1 a 7, editable en Ajustes).
   - **Punto:** al llegar a un punto fijo selecciona IN-01…IN-10 u OUT-01/02. La casilla *Interior* se marca sola con IN-xx y se desmarca con OUT-xx.
   - **⚡ Prueba de velocidad:** se lanza a mano en cada punto fijo. Marca *Auto cada 250 m* para que se lance sola en exteriores.
6. Pulsa **DETENER REGISTRO**. Los archivos quedan en **Descargas/MonitorCeldas** y puedes enviarlos con **Compartir**.

**Sincronización entre teléfonos:** las muestras se toman alineadas al segundo exacto del reloj (hh:mm:ss.000). Con la hora automática de la red activada, los teléfonos de ambos operadores muestrean en el mismo instante y los CSV se pueden unir por `timestamp`.

## Archivos generados (por sesión)

Nombre base: `MC_<modelo>_<MCCMNC>_<AAAAMMDD_HHMMSS>`. Los archivos van separados por `;`, con punto decimal y codificación UTF-8. **No los abras ni los guardes con Excel**: altera la columna de hora. Usa pandas: `pd.read_csv(f, sep=';')`.

| Archivo | Contenido |
|---|---|
| `_muestras.csv` | Una fila por segundo con celda servidora, GPS, 3 mejores vecinas, tráfico y resultados de pruebas |
| `_vecinas.csv` | Todas las vecinas reportadas en cada segundo |
| `_eventos.csv` | Inicio y fin, cambios de tramo, punto e interior, cambios de celda (handover) e inicio y fin de pruebas |
| `_pruebas.csv` | Una fila por prueba de velocidad, con la celda y la posición al inicio de la prueba |
| `.kml` | Ruta para Google Earth coloreada por RSRP (y SINR, en una capa oculta) más las pruebas de velocidad |

### Columnas de `_muestras.csv`

| Columna | Descripción |
|---|---|
| `timestamp` | Hora ISO 8601 con desfase horario, p. ej. `2026-10-29T10:00:01.002-05:00` |
| `epoch_ms` | La misma hora en milisegundos Unix |
| `gps_time` | Hora del fix GPS (útil para verificar la sincronización) |
| `lat`, `lon`, `gps_acc_m`, `alt_m`, `speed_kmh` | Posición GPS. Queda **vacía si el último fix tiene más de 10 s** (interiores): no se inventan posiciones |
| `gps_age_s` | Antigüedad del último fix |
| `operator`, `mcc_mnc` | Operador de red, p. ej. `716-15` |
| `tech` | Tecnología de la celda servidora: LTE, NR, WCDMA, GSM, SIN_SERVICIO |
| `net_type` | Tipo de red de datos que reporta Android |
| `display` | Indicador que muestra el teléfono: LTE_CA, NR_NSA, etc. |
| `band`, `arfcn`, `dl_mhz` | Banda (B28, B2, n78…), canal y frecuencia de bajada (TS 36.101) |
| `pci`, `cell_id`, `enb`, `sector`, `tac` | Identidad de la celda (ECI = eNB·256 + sector). En 3G/2G: pci=PSC/BSIC, tac=LAC |
| `rsrp`, `rsrq`, `rssi`, `sinr`, `cqi`, `ta`, `bw_mhz` | Niveles LTE (en NR SA: SS-RSRP/RSRQ/SINR) |
| `rscp`, `ecno` | Niveles 3G |
| `nr_*` | Datos de la pata 5G NR (NSA) si existe |
| `n_vecinas`, `v1_*`…`v3_*` | Número de vecinas y las 3 más fuertes |
| `rx_kbps`, `tx_kbps` | Throughput real de datos móviles en ese segundo (pasivo, todo el tráfico del teléfono) |
| `cell_age_ms` | Antigüedad del dato de celda que entregó el módem |
| `segmento`, `punto`, `indoor` | Lo que marcaste en la app (indoor 1/0) |
| `evento` | Eventos ocurridos en ese segundo (CAMBIO_CELDA, PUNTO, PRUEBA_INICIO…) |
| `test_id` | Id de la prueba en curso (T1, T2…), presente en todas las filas mientras dura la prueba |
| `dl_mbps`, `ul_mbps`, `latency_ms`, `jitter_ms`, `loss_pct` | Resultados, solo en la fila en que termina la prueba |

Los valores que el teléfono no reporta, o que están fuera del rango válido de 3GPP, quedan **vacíos**.

## Prueba de velocidad: cómo mide

- **Red:** fuerza la red móvil aunque haya WiFi. Aun así se recomienda apagar el WiFi.
- **Latencia y jitter:** 10 pings ICMP a `1.1.1.1`. Si el ping no está disponible, se usan 10 peticiones HTTP. La latencia es la mediana; el jitter es el promedio de las diferencias entre pings consecutivos. La pérdida de paquetes solo se mide con ICMP.
- **Descarga y subida:** 4 conexiones en paralelo durante 10 s cada una, contra los servidores de Cloudflare (`speed.cloudflare.com`). El cálculo descarta los primeros 2 s (arranque TCP).
- Todo es configurable en **Ajustes**: URL de servidor propio (p. ej. LibreSpeed), duración, conexiones e intervalo automático.
- Los resultados no son idénticos a los de Ookla (otro servidor y otro método), pero son comparables. Conviene declararlo en el informe.

## Limitaciones (iguales a las de NetMonitor sin root)

- Solo se accede a lo que Android entrega por la API oficial: nada de capa 3 (RRC/NAS), MCS ni BLER.
- SINR, CQI, TA y RSSI dependen del fabricante del módem. Si el teléfono no los reporta, la columna queda vacía.
- Algunos equipos actualizan la celda cada 2–10 s aunque se pida cada 1 s. `cell_age_ms` indica qué tan fresco es cada dato.
- Cada teléfono mide el SIM de datos activo. Con dos operadores se usan dos teléfonos, como en la metodología del informe.

## Compilar localmente

Con Android Studio (o JDK 17 y el SDK de Android): `./gradlew assembleDebug`. La app no usa librerías externas, solo el SDK de Android.
