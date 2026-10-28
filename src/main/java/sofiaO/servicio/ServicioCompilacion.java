package sofiaO.servicio;

import org.antlr.v4.runtime.*;
import sofiaO.Traductor.CEmitter;
import sofiaO.Traductor.Quadruple;
import sofiaO.Traductor.QuadrupleGenerator;
import sofiaO.Traductor.RiscVEmitter;
import sofiaO.ast.Declaration;
import sofiaO.ast.declaracion.ClassDeclNode;
import sofiaO.ast.declaracion.ImportNode;
import sofiaO.ast.declaracion.ProgramNode;
import sofiaO.lexer.CustomYLanguageLexer;
import sofiaO.parser.*;
import sofiaO.semantico.Compilador;
import sofiaO.semantico.SemanticException;
import sofiaO.util.TypeMapper;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Ejecuta todo el proceso de compilación sin imprimir nada por consola:
 * léxico -> sintáctico -> semántico -> cuartetas -> código C.
 * Devuelve un {@link ResultadoCompilacion} que la interfaz gráfica sabe mostrar.
 *
 * Es la misma secuencia que hacía Main, pero: lee los archivos del árbol de trabajo
 * (siguiendo los import del .pig), junta TODOS los errores en vez de imprimirlos, y
 * se detiene antes de generar código si hubo alguno.
 */
public class ServicioCompilacion {

    private static final int MAX_ERRORES_POR_ARCHIVO = 100;
    private static final Pattern UBICACION_EN_MENSAJE = Pattern.compile("\\s*\\(línea \\d+\\)");

    private static final class ArchivoAnalizado {
        final Path archivo;
        final Lenguaje lenguaje;
        final ProgramNode ast;

        ArchivoAnalizado(Path archivo, Lenguaje lenguaje, ProgramNode ast) {
            this.archivo = archivo;
            this.lenguaje = lenguaje;
            this.ast = ast;
        }
    }

    // =====================================================================
    // Compilar un programa Pig Latin con todos sus imports
    // =====================================================================

    /**
     * @param archivoPig programa principal (.pig)
     * @param raiz       carpeta de trabajo, contra la que se resuelven los import (puede ser null)
     * @param fuente     de dónde se lee el texto de cada archivo
     */
    public synchronized ResultadoCompilacion compilarPrograma(Path archivoPig, Path raiz, FuenteArchivos fuente) {
        ResultadoCompilacion r = new ResultadoCompilacion();
        TypeMapper.reiniciar();
        r.archivos.add(archivoPig);

        Path carpetaBase = raiz != null ? raiz : archivoPig.toAbsolutePath().getParent();

        // 1. El programa principal debe ser válido léxica y sintácticamente antes de mirar sus imports.
        ProgramNode astPig = leerYParsear(archivoPig, Lenguaje.PIG, fuente, r.errores);
        if (astPig == null) return ordenar(r);
        astPig.paquete = calcularPaquete(archivoPig, carpetaBase);

        // 2. Buscar y analizar cada archivo importado.
        List<ArchivoAnalizado> archivosY = new ArrayList<>();
        List<ArchivoAnalizado> archivosZ = new ArrayList<>();
        Set<Path> yaCargados = new HashSet<>();
        yaCargados.add(archivoPig.toAbsolutePath().normalize());

        for (ImportNode imp : astPig.imports) {
            Path resuelto = resolverImport(imp.ruta, archivoPig, carpetaBase);
            if (resuelto == null) {
                r.errores.add(new ErrorCompilador(ErrorCompilador.Tipo.SEMANTICO,
                        "No se encontró el archivo importado '" + imp.ruta + "'. Debe existir como "
                                + rutaEsperada(imp.ruta) + " dentro de la carpeta de trabajo.",
                        archivoPig, imp.getLine(), imp.getColumn(), 0));
                continue;
            }
            if (!yaCargados.add(resuelto.toAbsolutePath().normalize())) continue;

            Lenguaje lenguaje = Lenguaje.de(resuelto).orElse(Lenguaje.Y);
            r.archivos.add(resuelto);
            ProgramNode ast = leerYParsear(resuelto, lenguaje, fuente, r.errores);
            if (ast == null) continue;
            ast.paquete = calcularPaquete(resuelto, carpetaBase);
            if (lenguaje == Lenguaje.ZETARIANO) validarNombreDeClase(resuelto, ast, r.errores);
            (lenguaje == Lenguaje.ZETARIANO ? archivosZ : archivosY).add(new ArchivoAnalizado(resuelto, lenguaje, ast));
        }
        if (!r.errores.isEmpty()) return ordenar(r);

        // 2b. Herencia: cargar los .z de las superclases que no se importaron y poner cada padre antes que sus hijas.
        resolverHerencia(archivosZ, archivoPig, carpetaBase, fuente, yaCargados, r);
        if (!r.errores.isEmpty()) return ordenar(r);

        // 3. Análisis semántico. Primero lo que se importa (.y, luego .z) y al final el .pig.
        Compilador compilador = new Compilador();
        try {
            for (ArchivoAnalizado y : archivosY) {
                compilador.cargarEstructurasYFunciones(y.ast);
                recogerErroresSemanticos(compilador, y.archivo, r.errores);
            }
            try {
                compilador.cargarClases(astsDe(archivosZ));
            } catch (SemanticException e) {
                r.errores.add(desdeExcepcion(e, archivoDe(archivosZ, compilador, archivoPig)));
            }
            for (ArchivoAnalizado z : archivosZ) recogerErroresSemanticos(compilador, z.archivo, r.errores);
            if (!r.errores.isEmpty()) return ordenar(r);
            compilador.compilarPrincipal(astPig);
            recogerErroresSemanticos(compilador, archivoPig, r.errores);
        } catch (SemanticException e) {
            r.errores.add(desdeExcepcion(e, archivoPig));
        } catch (RuntimeException e) {
            r.errores.add(errorInterno(e, archivoPig));
        }
        if (!r.errores.isEmpty()) return ordenar(r);

        // 4. Cuartetas y código C.
        List<ProgramNode> ordenDeGeneracion = new ArrayList<>();
        archivosY.forEach(a -> ordenDeGeneracion.add(a.ast));
        archivosZ.forEach(a -> ordenDeGeneracion.add(a.ast));
        ordenDeGeneracion.add(astPig);
        generarCodigo(r, compilador, ordenDeGeneracion, archivoPig);
        return ordenar(r);
    }

    // =====================================================================
    // Validar un .y o un .z por separado
    // =====================================================================

    /** Valida un solo archivo (.y o .z) sin necesidad de un programa .pig que lo importe. */
    public synchronized ResultadoCompilacion validarArchivo(Path archivo, Path raiz, FuenteArchivos fuente) {
        Optional<Lenguaje> lenguaje = Lenguaje.de(archivo);
        if (lenguaje.isPresent() && lenguaje.get() == Lenguaje.PIG) {
            return compilarPrograma(archivo, raiz, fuente);
        }

        ResultadoCompilacion r = new ResultadoCompilacion();
        TypeMapper.reiniciar();
        r.archivos.add(archivo);
        if (lenguaje.isEmpty()) {
            r.errores.add(new ErrorCompilador(ErrorCompilador.Tipo.INTERNO,
                    "Extensión no reconocida. Los archivos deben terminar en .y, .z o .pig.", archivo, 1, 0, 0));
            return r;
        }

        ProgramNode ast = leerYParsear(archivo, lenguaje.get(), fuente, r.errores);
        if (ast == null) return ordenar(r);
        ast.paquete = calcularPaquete(archivo, raiz);
        if (lenguaje.get() == Lenguaje.ZETARIANO) validarNombreDeClase(archivo, ast, r.errores);
        if (!r.errores.isEmpty()) return ordenar(r);

        // Un .z que hereda de otra clase necesita también el .z de su superclase (y las de más arriba).
        List<ArchivoAnalizado> archivosZ = new ArrayList<>();
        if (lenguaje.get() == Lenguaje.ZETARIANO) {
            archivosZ.add(new ArchivoAnalizado(archivo, Lenguaje.ZETARIANO, ast));
            Set<Path> yaCargados = new HashSet<>();
            yaCargados.add(archivo.toAbsolutePath().normalize());
            Path carpetaBase = raiz != null ? raiz : archivo.toAbsolutePath().getParent();
            resolverHerencia(archivosZ, archivo, carpetaBase, fuente, yaCargados, r);
            if (!r.errores.isEmpty()) return ordenar(r);
        }

        Compilador compilador = new Compilador();
        List<ProgramNode> aGenerar = new ArrayList<>();
        try {
            if (lenguaje.get() == Lenguaje.ZETARIANO) {
                try {
                    compilador.cargarClases(astsDe(archivosZ));
                } catch (SemanticException e) {
                    r.errores.add(desdeExcepcion(e, archivoDe(archivosZ, compilador, archivo)));
                }
                for (ArchivoAnalizado z : archivosZ) {
                    recogerErroresSemanticos(compilador, z.archivo, r.errores);
                    aGenerar.add(z.ast);
                }
            } else {
                compilador.cargarEstructurasYFunciones(ast);
                recogerErroresSemanticos(compilador, archivo, r.errores);
                aGenerar.add(ast);
            }
        } catch (SemanticException e) {
            r.errores.add(desdeExcepcion(e, archivo));
        } catch (RuntimeException e) {
            r.errores.add(errorInterno(e, archivo));
        }
        if (!r.errores.isEmpty()) return ordenar(r);

        generarCodigo(r, compilador, aGenerar, archivo);
        return ordenar(r);
    }

    // =====================================================================
    // Piezas internas
    // =====================================================================

    private void generarCodigo(ResultadoCompilacion r, Compilador compilador,
                               List<ProgramNode> programas, Path archivoParaErrores) {
        try {
            QuadrupleGenerator generador = new QuadrupleGenerator();
            generador.contexto = compilador.contexto;
            for (ProgramNode p : programas) generador.visit(p);

            StringBuilder texto = new StringBuilder();
            int i = 0;
            for (Quadruple q : generador.cuartetas) {
                String arg2 = q.arg2 != null ? q.arg2
                        : (q.extraArgs.isEmpty() ? "" : String.join(", ", q.extraArgs));
                String instruccion = q.toString();
                r.cuartetas.add(new FilaCuarteta(i++, q.op, nvl(q.arg1), arg2, nvl(q.result), instruccion));
                texto.append(instruccion).append('\n');
            }
            r.cuartetasTexto = texto.toString();

            CEmitter emisor = new CEmitter();
            emisor.tiposUsuarioDeLugares = generador.tiposUsuarioDeLugares;
            emisor.setLayoutsEstructuras(compilador.contexto.obtenerLayoutsParaCEmitter());
            emisor.setDimensionesCamposEstructuras(compilador.contexto.obtenerDimensionesCamposParaCEmitter());
            r.codigoC = emisor.emitir(generador.cuartetas, generador.tiposDeLugares);

            // Fase 2: la misma lista de cuartetas se traduce también a RISC-V (pila y heap explícitos).
            try {
                RiscVEmitter riscv = new RiscVEmitter();
                riscv.tiposUsuarioDeLugares = generador.tiposUsuarioDeLugares;
                riscv.setLayouts(compilador.contexto.layoutsEstructuras, compilador.contexto.layoutsClases);
                riscv.setDimensionesCampos(compilador.contexto.obtenerDimensionesCamposParaCEmitter());
                riscv.setSuperclases(compilador.contexto.superclases);
                r.codigoRiscV = riscv.emitir(generador.cuartetas);
                r.reporteMemoria = riscv.getReporteMemoria();
            } catch (RuntimeException e) {
                r.errores.add(errorInterno(e, archivoParaErrores));
            }

            r.resumen.add("Estructuras: " + ordenados(compilador.contexto.structs.keySet()));
            r.resumen.add("Clases: " + ordenados(compilador.contexto.classes.keySet()));
            r.resumen.add("Funciones: " + ordenados(compilador.contexto.funciones.keySet()));
        } catch (RuntimeException e) {
            r.errores.add(errorInterno(e, archivoParaErrores));
        }
    }

    private static List<String> ordenados(Collection<String> nombres) {
        List<String> lista = new ArrayList<>(nombres);
        Collections.sort(lista);
        return lista;
    }

    /** Lee el archivo y lo convierte en AST. Devuelve null si hubo errores léxicos o sintácticos. */
    private ProgramNode leerYParsear(Path archivo, Lenguaje lenguaje, FuenteArchivos fuente,
                                     List<ErrorCompilador> errores) {
        String texto;
        try {
            texto = fuente.leer(archivo);
        } catch (IOException e) {
            errores.add(new ErrorCompilador(ErrorCompilador.Tipo.INTERNO,
                    "No se pudo leer el archivo: " + e.getMessage(), archivo, 1, 0, 0));
            return null;
        }

        List<ErrorCompilador> lexicos = new ArrayList<>();
        List<ErrorCompilador> sintacticos = new ArrayList<>();
        ProgramNode ast = null;

        try {
            CharStream entrada = CharStreams.fromString(texto);
            ColectorErroresAntlr colectorLexico = new ColectorErroresAntlr(lexicos, archivo, ErrorCompilador.Tipo.LEXICO);
            ColectorErroresAntlr colectorSintactico = new ColectorErroresAntlr(sintacticos, archivo, ErrorCompilador.Tipo.SINTACTICO);

            switch (lenguaje) {
                case Y -> {
                    Lexer lexer = new CustomYLanguageLexer(entrada);
                    reemplazarListeners(lexer, colectorLexico);
                    YLanguageParser parser = new YLanguageParser(new CommonTokenStream(lexer));
                    reemplazarListeners(parser, colectorSintactico);
                    var arbol = parser.programa();
                    if (lexicos.isEmpty() && sintacticos.isEmpty()) {
                        ast = (ProgramNode) new YLanguageASTBuilder().visit(arbol);
                    }
                }
                case ZETARIANO -> {
                    Lexer lexer = new ZetarianoLanguageLexer(entrada);
                    reemplazarListeners(lexer, colectorLexico);
                    ZetarianoLanguageParser parser = new ZetarianoLanguageParser(new CommonTokenStream(lexer));
                    reemplazarListeners(parser, colectorSintactico);
                    var arbol = parser.programa();
                    if (lexicos.isEmpty() && sintacticos.isEmpty()) {
                        ast = (ProgramNode) new ZetarianoASTBuilder().visit(arbol);
                    }
                }
                case PIG -> {
                    Lexer lexer = new PigLatinLanguageLexer(entrada);
                    reemplazarListeners(lexer, colectorLexico);
                    PigLatinLanguageParser parser = new PigLatinLanguageParser(new CommonTokenStream(lexer));
                    reemplazarListeners(parser, colectorSintactico);
                    var arbol = parser.programa();
                    if (lexicos.isEmpty() && sintacticos.isEmpty()) {
                        ast = (ProgramNode) new PigLatinASTBuilder().visit(arbol);
                    }
                }
            }
        } catch (RuntimeException e) {
            errores.add(errorInterno(e, archivo));
            return null;
        }

        // Un carácter inválido suele provocar además un error sintáctico en la misma línea;
        // solo se muestra el léxico, que es la causa real.
        Set<Integer> lineasConErrorLexico = new HashSet<>();
        lexicos.forEach(e -> lineasConErrorLexico.add(e.linea));
        sintacticos.removeIf(e -> lineasConErrorLexico.contains(e.linea));

        agregarSinRepetir(errores, lexicos);
        agregarSinRepetir(errores, sintacticos);

        if (!lexicos.isEmpty() || !sintacticos.isEmpty()) return null;
        if (ast == null) {
            errores.add(new ErrorCompilador(ErrorCompilador.Tipo.INTERNO,
                    "No se pudo construir el árbol de " + lenguaje.nombre + " para este archivo.", archivo, 1, 0, 0));
        }
        return ast;
    }

    private static void reemplazarListeners(Recognizer<?, ?> reconocedor, ANTLRErrorListener listener) {
        reconocedor.removeErrorListeners();
        reconocedor.addErrorListener(listener);
    }

    private static void agregarSinRepetir(List<ErrorCompilador> destino, List<ErrorCompilador> nuevos) {
        Set<String> claves = new HashSet<>();
        int delArchivo = 0;
        for (ErrorCompilador e : nuevos) {
            String clave = e.archivo + ":" + e.linea + ":" + e.columna + ":" + e.descripcion;
            if (!claves.add(clave)) continue;
            if (++delArchivo > MAX_ERRORES_POR_ARCHIVO) break;
            destino.add(e);
        }
    }

    /** El enunciado exige que el archivo .z se llame igual que la clase que define. */
    private void validarNombreDeClase(Path archivo, ProgramNode ast, List<ErrorCompilador> errores) {
        String esperado = Lenguaje.nombreBase(archivo);
        for (Declaration d : ast.declaraciones) {
            if (d instanceof ClassDeclNode clase && !clase.nombre.equals(esperado)) {
                errores.add(new ErrorCompilador(ErrorCompilador.Tipo.SEMANTICO,
                        "El archivo debe llamarse igual que la clase que define: la clase es '" + clase.nombre
                                + "' pero el archivo se llama '" + archivo.getFileName() + "' (debería ser '"
                                + clase.nombre + ".z').",
                        archivo, clase.getLine(), clase.getColumn(), 0));
            }
        }
    }

    private void recogerErroresSemanticos(Compilador compilador, Path archivo, List<ErrorCompilador> destino) {
        List<SemanticException> pendientes = compilador.getAnalizador().getErrores();
        for (SemanticException e : pendientes) destino.add(desdeExcepcion(e, archivo));
        pendientes.clear();
    }

    private static List<ProgramNode> astsDe(List<ArchivoAnalizado> archivos) {
        List<ProgramNode> asts = new ArrayList<>();
        for (ArchivoAnalizado a : archivos) asts.add(a.ast);
        return asts;
    }

    /** Archivo .z que se estaba analizando cuando falló la carga de clases. */
    private static Path archivoDe(List<ArchivoAnalizado> archivos, Compilador compilador, Path porDefecto) {
        ProgramNode enCurso = compilador.getAnalizador().getProgramaEnCurso();
        for (ArchivoAnalizado a : archivos) if (a.ast == enCurso) return a.archivo;
        return porDefecto;
    }

    private static ErrorCompilador desdeExcepcion(SemanticException e, Path archivo) {
        String mensaje = UBICACION_EN_MENSAJE.matcher(e.getMessage()).replaceAll("").trim();
        return new ErrorCompilador(ErrorCompilador.Tipo.SEMANTICO, mensaje, archivo,
                e.tieneUbicacion() ? e.getLinea() : 1, Math.max(0, e.getColumna()), 0);
    }

    private static ErrorCompilador errorInterno(RuntimeException e, Path archivo) {
        String detalle = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        return new ErrorCompilador(ErrorCompilador.Tipo.INTERNO,
                "Error inesperado del compilador: " + detalle, archivo, 1, 0, 0);
    }

    // ---------------- herencia ----------------

    private static ClassDeclNode claseDe(ArchivoAnalizado z) {
        for (Declaration d : z.ast.declaraciones) if (d instanceof ClassDeclNode c) return c;
        return null;
    }

    /**
     * Prepara las clases .z para el análisis de herencia:
     *  1) si una clase hereda de otra cuyo .z no se importó, lo busca en la carpeta de trabajo por su nombre
     *     (Padre.z) y lo carga también, y así hacia arriba;
     *  2) reordena la lista para que cada superclase vaya ANTES que sus hijas (el semántico las necesita en ese orden);
     *  3) detecta la herencia circular.
     * Modifica 'archivosZ' en su sitio. Los problemas se agregan a 'r.errores'.
     */
    private void resolverHerencia(List<ArchivoAnalizado> archivosZ, Path archivoOrigen, Path carpetaBase,
                                  FuenteArchivos fuente, Set<Path> yaCargados, ResultadoCompilacion r) {
        Set<String> nombres = new HashSet<>();
        for (ArchivoAnalizado z : archivosZ) {
            ClassDeclNode c = claseDe(z);
            if (c != null) nombres.add(c.nombre);
        }

        for (int i = 0; i < archivosZ.size(); i++) {           // la lista puede crecer mientras se recorre
            ArchivoAnalizado hija = archivosZ.get(i);
            ClassDeclNode clase = claseDe(hija);
            if (clase == null || clase.superclase == null || clase.superclase.equals(clase.nombre)
                    || nombres.contains(clase.superclase)) continue;

            Path padre = resolverImport(clase.superclase + ".z", archivoOrigen, carpetaBase);
            if (padre == null || !yaCargados.add(padre.toAbsolutePath().normalize())) continue;   // el semántico avisa

            r.archivos.add(padre);
            ProgramNode ast = leerYParsear(padre, Lenguaje.ZETARIANO, fuente, r.errores);
            if (ast == null) continue;
            ast.paquete = calcularPaquete(padre, carpetaBase);
            validarNombreDeClase(padre, ast, r.errores);
            ArchivoAnalizado cargado = new ArchivoAnalizado(padre, Lenguaje.ZETARIANO, ast);
            archivosZ.add(cargado);
            ClassDeclNode nueva = claseDe(cargado);
            if (nueva != null) nombres.add(nueva.nombre);
        }
        if (!r.errores.isEmpty()) return;

        Map<String, ArchivoAnalizado> porClase = new LinkedHashMap<>();
        for (ArchivoAnalizado z : archivosZ) {
            ClassDeclNode c = claseDe(z);
            if (c != null) porClase.putIfAbsent(c.nombre, z);
        }
        List<ArchivoAnalizado> ordenadas = new ArrayList<>();
        Set<String> colocadas = new HashSet<>();
        for (ArchivoAnalizado z : archivosZ) {
            ClassDeclNode c = claseDe(z);
            if (c == null) { ordenadas.add(z); continue; }
            colocarConSusAncestros(c.nombre, porClase, colocadas, new ArrayList<>(), ordenadas, r);
        }
        archivosZ.clear();
        archivosZ.addAll(ordenadas);
    }

    private void colocarConSusAncestros(String nombre, Map<String, ArchivoAnalizado> porClase, Set<String> colocadas,
                                        List<String> camino, List<ArchivoAnalizado> ordenadas, ResultadoCompilacion r) {
        if (colocadas.contains(nombre)) return;
        ArchivoAnalizado z = porClase.get(nombre);
        if (z == null) return;
        if (camino.contains(nombre)) {
            ClassDeclNode c = claseDe(z);
            List<String> ciclo = new ArrayList<>(camino.subList(camino.indexOf(nombre), camino.size()));
            ciclo.add(nombre);
            r.errores.add(new ErrorCompilador(ErrorCompilador.Tipo.SEMANTICO,
                    "Herencia circular entre clases: " + String.join(" -> ", ciclo) + ".",
                    z.archivo, c.lineaSuperclase > 0 ? c.lineaSuperclase : c.getLine(), c.columnaSuperclase, 0));
            colocadas.add(nombre);
            return;
        }
        camino.add(nombre);
        ClassDeclNode clase = claseDe(z);
        if (clase != null && clase.superclase != null) {
            colocarConSusAncestros(clase.superclase, porClase, colocadas, camino, ordenadas, r);
        }
        camino.remove(camino.size() - 1);
        if (colocadas.add(nombre)) ordenadas.add(z);
    }

    // ---------------- paquetes (encapsulamiento) ----------------

    /**
     * El paquete de un archivo es su carpeta relativa a la carpeta de trabajo, con '.' como separador:
     * raiz/modelo/Persona.z -> "modelo"; raiz/a/b/X.z -> "a.b"; un archivo en la raíz -> "" (paquete por
     * defecto). Es el mismo criterio de los import (carpeta.Objeto1.z). Si no hay carpeta de trabajo o el
     * archivo queda fuera de ella, se usa el paquete por defecto.
     */
    static String calcularPaquete(Path archivo, Path raiz) {
        if (archivo == null || raiz == null) return "";
        Path base = raiz.toAbsolutePath().normalize();
        Path carpeta = archivo.toAbsolutePath().normalize().getParent();
        if (carpeta == null || !carpeta.startsWith(base)) return "";
        Path relativa = base.relativize(carpeta);
        StringBuilder paquete = new StringBuilder();
        for (Path parte : relativa) {
            String nombre = parte.toString();
            if (nombre.isEmpty()) continue;
            if (paquete.length() > 0) paquete.append('.');
            paquete.append(nombre);
        }
        return paquete.toString();
    }

    // ---------------- imports ----------------

    /** carpeta.Objeto1.z -> carpeta/Objeto1.z */
    private static String rutaEsperada(String rutaImport) {
        int ultimoPunto = rutaImport.lastIndexOf('.');
        if (ultimoPunto < 0) return rutaImport;
        return rutaImport.substring(0, ultimoPunto).replace('.', '/') + rutaImport.substring(ultimoPunto);
    }

    private static Path resolverImport(String rutaImport, Path archivoPig, Path raiz) {
        String relativa = rutaEsperada(rutaImport).replace('/', File.separatorChar);

        List<Path> candidatos = new ArrayList<>();
        if (raiz != null) candidatos.add(raiz.resolve(relativa));
        Path carpetaDelPig = archivoPig.toAbsolutePath().getParent();
        if (carpetaDelPig != null) candidatos.add(carpetaDelPig.resolve(relativa));
        for (Path c : candidatos) {
            if (Files.isRegularFile(c)) return c;
        }

        // Último recurso: buscar solo por nombre de archivo dentro de la carpeta de trabajo.
        if (raiz != null && Files.isDirectory(raiz)) {
            String nombre = Path.of(relativa).getFileName().toString();
            try (Stream<Path> recorrido = Files.walk(raiz, 8)) {
                return recorrido.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().equals(nombre))
                        .findFirst().orElse(null);
            } catch (IOException ignorada) {
                return null;
            }
        }
        return null;
    }

    private static ResultadoCompilacion ordenar(ResultadoCompilacion r) {
        r.errores.sort(Comparator
                .comparing((ErrorCompilador e) -> e.nombreArchivo())
                .thenComparingInt(e -> e.linea)
                .thenComparingInt(e -> e.columna));
        return r;
    }

    private static String nvl(String s) { return s == null ? "" : s; }
}
