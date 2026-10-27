package sofiaO.servicio;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Ejecuta el ensamblador RISC-V generado dentro del propio programa, sin abrir RARS a mano.
 * <p>
 * Usa RARS (simulador RISC-V, licencia MIT) en modo de línea de comandos: se escribe el código en una
 * carpeta temporal y se lanza {@code java -jar rars.jar nc sm programa.asm} con la misma JVM que corre
 * esta aplicación. La entrada y la salida del programa (ecalls de leer e imprimir) pasan por las
 * tuberías del proceso, igual que con el código C.
 * <p>
 * El jar se busca, en este orden: (1) la variable de entorno / propiedad {@code RARS_JAR},
 * (2) un archivo {@code rars.jar} junto al directorio de trabajo, (3) el recurso empaquetado
 * {@code /rars/rars.jar} dentro de la aplicación.
 */
public final class EjecutorRiscV {

    private EjecutorRiscV() {}

    public static final class Preparacion {
        public final boolean exito;
        public final String mensaje;
        public final Process proceso;
        public final Path carpetaTemporal;

        private Preparacion(boolean exito, String mensaje, Process proceso, Path carpetaTemporal) {
            this.exito = exito;
            this.mensaje = mensaje;
            this.proceso = proceso;
            this.carpetaTemporal = carpetaTemporal;
        }
    }

    /** Escribe el código en una carpeta temporal y arranca RARS. No lanza excepción por un jar faltante: queda en el resultado. */
    public static Preparacion iniciar(String codigoRiscV, String nombreBase) throws IOException {
        Path carpeta = Files.createTempDirectory("extraterrestre3d_rv_");
        Path jar = localizarJar(carpeta);
        if (jar == null) {
            EjecutorC.borrarCarpeta(carpeta);
            return new Preparacion(false,
                    "No se encontró el simulador RARS (rars.jar).\n"
                            + "Colócalo junto a la aplicación (archivo rars.jar), indica su ruta con la variable RARS_JAR,\n"
                            + "o agrégalo como recurso /rars/rars.jar. Descarga: https://github.com/TheThirdOne/rars/releases",
                    null, null);
        }

        String base = nombreBase == null || nombreBase.isBlank() ? "programa" : nombreBase.replaceAll("[^A-Za-z0-9_.-]", "_");
        Path fuente = carpeta.resolve(base + ".asm");
        Files.writeString(fuente, codigoRiscV, StandardCharsets.UTF_8);

        String java = Path.of(System.getProperty("java.home"), "bin", esWindows() ? "java.exe" : "java").toString();
        // nc: sin copyright; sm: empezar en la etiqueta global 'main'
        ProcessBuilder pb = new ProcessBuilder(java, "-jar", jar.toAbsolutePath().toString(),
                "nc", "sm", fuente.getFileName().toString());
        pb.directory(carpeta.toFile());
        pb.redirectErrorStream(true);
        // Evita que opciones del entorno de la JVM ensucien la consola del programa.
        pb.environment().remove("JAVA_TOOL_OPTIONS");
        pb.environment().remove("_JAVA_OPTIONS");
        pb.environment().remove("JDK_JAVA_OPTIONS");
        return new Preparacion(true, "", pb.start(), carpeta);
    }

    private static Path localizarJar(Path carpetaTemporal) throws IOException {
        String indicado = System.getProperty("RARS_JAR", System.getenv("RARS_JAR"));
        if (indicado != null && !indicado.isBlank()) {
            Path p = Path.of(indicado);
            if (Files.isRegularFile(p)) return p;
        }
        Path local = Path.of("rars.jar");
        if (Files.isRegularFile(local)) return local;

        try (InputStream recurso = EjecutorRiscV.class.getResourceAsStream("/rars/rars.jar")) {
            if (recurso == null) return null;
            Path destino = carpetaTemporal.resolve("rars.jar");
            Files.copy(recurso, destino, StandardCopyOption.REPLACE_EXISTING);
            return destino;
        }
    }

    private static boolean esWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }
}
