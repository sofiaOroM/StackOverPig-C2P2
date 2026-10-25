package sofiaO.semantico;

import sofiaO.ast.ASTNode;
import sofiaO.ast.ASTVisitor;
import sofiaO.ast.Expression;
import sofiaO.ast.Statement;
import sofiaO.ast.declaracion.*;
import sofiaO.ast.sentencia.*;
import sofiaO.ast.expresion.*;
import sofiaO.util.Type;
import sofiaO.util.TypeMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Analizador semántico único, compartido por Y?, Zetariano y Pig Latin.
 */
public class SemanticAnalyzer implements ASTVisitor<Type> {

    private SymbolTable scopeActual;
    private final ProgramContext contexto;
    private ResolvedType tipoRetornoFuncionActual = null;
    private int profundidadCiclo = 0;
    private String claseActual = null;
    /** Paquete del archivo que se está analizando ("" = por defecto). Lo usa el encapsulamiento. */
    private String paqueteActual = "";

    private final List<SemanticException> errores = new ArrayList<>();
    private static final Pattern LINEA_EN_MENSAJE = Pattern.compile("\\(línea (\\d+)\\)");
    private static final Pattern TIPO_ERROR = Pattern.compile("(?<![A-Za-z])ERROR(?![A-Za-z])");

    public List<SemanticException> getErrores() { return errores; }

    private void analizarAislado(ASTNode nodo) {
        SymbolTable scopePrevio = scopeActual;
        int cicloPrevio = profundidadCiclo;
        ResolvedType retornoPrevio = tipoRetornoFuncionActual;
        try {
            nodo.accept(this);
        } catch (SemanticException e) {
            scopeActual = scopePrevio;
            profundidadCiclo = cicloPrevio;
            tipoRetornoFuncionActual = retornoPrevio;

            // Un tipo ERROR en el mensaje es consecuencia de un fallo anterior: no se repite.
            if (!TIPO_ERROR.matcher(e.getMessage()).find()) {
                if (!e.tieneUbicacion()) {
                    int linea = nodo.getLine();
                    int columna = nodo.getColumn();
                    Matcher m = LINEA_EN_MENSAJE.matcher(e.getMessage());
                    if (m.find()) {
                        int lineaMensaje = Integer.parseInt(m.group(1));
                        if (lineaMensaje != linea) { linea = lineaMensaje; columna = 0; }
                    }
                    e.setUbicacion(linea, columna);
                }
                errores.add(e);
            }
            if (nodo instanceof VarDeclNode v) declararTrasError(v);
        }
    }

    /** Si una declaración falla, se registra igual para no provocar errores en cascada. */
    private void declararTrasError(VarDeclNode v) {
        if (scopeActual.existeEnScopeActual(v.nombre)) return;
        if (contexto.funciones.containsKey(v.nombre)) return;
        try {
            ResolvedType rt = resolverTipoDeclarado(v.tipo, v.getLine());
            scopeActual.declarar(v.nombre, rt.tipo(), rt.tipoUsuario(), v.getLine(), v.getColumn());
        } catch (SemanticException ignorada) {
            scopeActual.declarar(v.nombre, Type.ERROR, null, v.getLine(), v.getColumn());
        }
    }

    public SemanticAnalyzer(SymbolTable scopeGlobal, ProgramContext contexto) {
        this.scopeActual = scopeGlobal;
        this.contexto = contexto;
    }

    public SemanticAnalyzer(SymbolTable scopeGlobal) {
        this(scopeGlobal, new ProgramContext());
    }

    public ProgramContext getContexto() { return contexto; }

    private void abrirScope() { scopeActual = new SymbolTable(scopeActual); }
    private void cerrarScope() { scopeActual = scopeActual.getScopePadre(); }

    private void visitarCuerpo(List<Statement> cuerpo) {
        abrirScope();
        for (Statement s : cuerpo) s.accept(this);
        cerrarScope();
    }

    private ResolvedType resolverTipoDeclarado(String texto, int linea) {
        if (texto == null || texto.equalsIgnoreCase("void")) {
            return ResolvedType.primitivo(Type.VOID);
        }
        Type primitivo = TypeMapper.deTexto(texto);
        if (primitivo != Type.ERROR) return ResolvedType.primitivo(primitivo);

        if (contexto.structs.containsKey(texto)) return new ResolvedType(Type.ESTRUCTURA, texto);
        if (contexto.classes.containsKey(texto)) return new ResolvedType(Type.CLASE, texto);

        throw new SemanticException("Tipo desconocido: '" + texto + "' (línea " + linea + "). "
                + "¿Falta importar el archivo .y/.z que lo define?");
    }

    private Map<String, ResolvedType> obtenerLayout(Type tipo, String tipoUsuario) {
        if (tipo == Type.ESTRUCTURA) return contexto.layoutsEstructuras.get(tipoUsuario);
        if (tipo == Type.CLASE) return contexto.layoutsClases.get(tipoUsuario);
        return null;
    }

    private String tipoUsuarioDe(ASTNode nodo) {
        return (nodo instanceof sofiaO.ast.BaseNode b) ? b.tipoUsuarioResuelto : null;
    }

    private String etiquetaTipo(Type tipo, String tipoUsuario) {
        return tipoUsuario != null ? tipoUsuario : tipo.toString();
    }

    /**
     * Cuenta cuántos valores escalares deja un literal de arreglo una vez
     * aplanado (mismo criterio que ExpressionGenerator.aplanarValores):
     * cada {} anidado que no sea una estructura es una sub-dimensión y se
     * cuenta recursivamente, no como un solo elemento.
     */
    private int contarValoresPlanos(ArrayLiteralNode n) {
        int total = 0;
        for (Expression valor : n.valores) {
            if (valor instanceof ArrayLiteralNode anidado && anidado.tipoResuelto != Type.ESTRUCTURA) {
                total += contarValoresPlanos(anidado);
            } else {
                total++;
            }
        }
        return total;
    }

    private void precargarAtributosDeClase(String nombreClase) {
        Map<String, ResolvedType> layout = contexto.layoutsClases.get(nombreClase);
        if (layout == null) return;
        for (var entrada : layout.entrySet()) {
            ResolvedType rt = entrada.getValue();
            scopeActual.declarar(entrada.getKey(), rt.tipo(), rt.tipoUsuario(), 0, 0);
        }
    }

    /** ¿La clase (o alguna de sus superclases) tiene un método con ese nombre? */
    private boolean tieneMetodo(String clase, String nombre) {
        int guarda = 0;
        for (String c = clase; c != null && guarda++ < 100; c = contexto.superclases.get(c)) {
            var porNombre = contexto.sobrecargasPorClase.get(c);
            if (porNombre != null && porNombre.containsKey(nombre)) return true;
        }
        return false;
    }

    /**
     * Elige la sobrecarga de 'nombre' que corresponde a los argumentos de la llamada, buscando primero en
     * la clase y luego en sus superclases. Deja la elegida en call.metodoResuelto para el generador.
     * Devuelve null si ninguna clase de la cadena tiene un método con ese nombre.
     */
    private FunctionDeclNode resolverMetodo(String clase, String nombre, CallSuffix call, int linea) {
        List<FunctionDeclNode> candidatos = new ArrayList<>();
        int guarda = 0;
        for (String c = clase; c != null && guarda++ < 100; c = contexto.superclases.get(c)) {
            var porNombre = contexto.sobrecargasPorClase.get(c);
            if (porNombre != null && porNombre.containsKey(nombre)) candidatos.addAll(porNombre.get(nombre));
        }
        if (candidatos.isEmpty()) return null;

        FunctionDeclNode elegido = candidatos.get(0);
        if (candidatos.size() > 1) {
            List<Type> tipos = new ArrayList<>();
            List<String> tiposUsr = new ArrayList<>();
            for (Expression a : call.argumentos) { tipos.add(a.accept(this)); tiposUsr.add(tipoUsuarioDe(a)); }
            FunctionDeclNode coincidente = null;
            for (FunctionDeclNode c : candidatos) {          // 1.º firma exacta, 2.º firma compatible
                if (coincideExacta(c.parametros, tipos, tiposUsr, linea)) { coincidente = c; break; }
            }
            if (coincidente == null) {
                for (FunctionDeclNode c : candidatos) {
                    if (coincideFirma(c.parametros, tipos, tiposUsr, linea)) { coincidente = c; break; }
                }
            }
            if (coincidente == null) {
                throw new SemanticException("Ningún método '" + nombre + "' de '" + clase + "' coincide con los "
                        + tipos.size() + " argumento(s) dado(s) (línea " + linea + ")");
            }
            elegido = coincidente;
        }
        call.metodoResuelto = elegido;
        return elegido;
    }

    private boolean coincideExacta(List<ParamNode> parametros, List<Type> tiposArgs, List<String> tiposUsuarioArgs, int linea) {
        if (parametros.size() != tiposArgs.size()) return false;
        for (int i = 0; i < parametros.size(); i++) {
            ResolvedType rtParam = resolverTipoDeclarado(parametros.get(i).tipo, linea);
            if (rtParam.tipo() != tiposArgs.get(i)) return false;
            if (!java.util.Objects.equals(rtParam.tipoUsuario(), tiposUsuarioArgs.get(i))) return false;
        }
        return true;
    }

    private void validarParametros(List<ParamNode> parametros, List<Expression> argumentos, String nombreCallable, int linea) {
        if (parametros.size() != argumentos.size()) {
            throw new SemanticException("La función/método '" + nombreCallable + "' espera "
                    + parametros.size() + " argumento(s), pero se recibieron " + argumentos.size()
                    + " (línea " + linea + ")");
        }
        for (int i = 0; i < parametros.size(); i++) {
            ParamNode p = parametros.get(i);
            Expression arg = argumentos.get(i);
            Type tipoArg = arg.accept(this);
            String tipoUsuarioArg = tipoUsuarioDe(arg);

            ResolvedType rtParam = resolverTipoDeclarado(p.tipo, linea);
            if (!TypeCoercionTable.compatibles(rtParam.tipo(), rtParam.tipoUsuario(), tipoArg, tipoUsuarioArg)) {
                throw new SemanticException("Tipo incompatible para el parámetro '" + p.nombre
                        + "' en '" + nombreCallable + "': se esperaba " + rtParam.etiqueta()
                        + " pero se obtuvo " + etiquetaTipo(tipoArg, tipoUsuarioArg)
                        + " (línea " + linea + ")");
            }
        }
    }

    // ================= ENCAPSULAMIENTO (fase 2) =================

    /** Atributo encontrado en la cadena de herencia: el nodo y la clase que realmente lo declara. */
    private record AtributoDeclarado(FieldNode nodo, String claseDeclarante) {}

    /** Busca el atributo en la clase y luego en sus superclases (hoy la cadena es solo la propia clase). */
    private AtributoDeclarado buscarAtributo(String clase, String nombre) {
        int guarda = 0;
        for (String c = clase; c != null && guarda++ < 100; c = contexto.superclases.get(c)) {
            ClassDeclNode decl = contexto.classes.get(c);
            if (decl == null) continue;
            for (FieldNode f : decl.atributos) {
                if (f.nombre.equals(nombre)) return new AtributoDeclarado(f, c);
            }
        }
        return null;
    }

    /** ¿'hija' es 'ancestro' o desciende de él? (usa el mapa de herencia, vacío mientras no haya 'extends'). */
    private boolean esSubclaseDe(String hija, String ancestro) {
        int guarda = 0;
        for (String c = hija; c != null && guarda++ < 100; c = contexto.superclases.get(c)) {
            if (c.equals(ancestro)) return true;
        }
        return false;
    }

    private boolean mismoPaquete(String claseDeclarante) {
        return Objects.equals(contexto.paquetesPorClase.getOrDefault(claseDeclarante, ""), paqueteActual);
    }

    /**
     * Regla de acceso para un miembro declarado en 'claseDeclarante', usado desde el código que se analiza
     * ahora (claseActual / paqueteActual; claseActual es null en un .pig o un .y):
     *   public    -> siempre
     *   private   -> solo dentro de la misma clase
     *   default   -> solo dentro del mismo paquete
     *   protected -> mismo paquete, o desde una subclase (aunque esté en otro paquete)
     */
    private boolean puedeAcceder(Visibilidad visibilidad, String claseDeclarante) {
        return switch (visibilidad) {
            case PUBLIC -> true;
            case PRIVATE -> claseActual != null && claseActual.equals(claseDeclarante);
            case DEFAULT -> mismoPaquete(claseDeclarante);
            case PROTECTED -> mismoPaquete(claseDeclarante)
                    || (claseActual != null && esSubclaseDe(claseActual, claseDeclarante));
        };
    }

    /** "'modelo'" / "por defecto" (se usa después de "paquete"). */
    private static String nombrePaquete(String paquete) {
        return paquete == null || paquete.isEmpty() ? "por defecto" : "'" + paquete + "'";
    }

    /**
     * Lanza el error semántico si el miembro no es accesible desde el código actual.
     * @param tipoMiembro "atributo", "método" o "constructor" (solo para el mensaje)
     */
    private void verificarAcceso(String tipoMiembro, String nombre, String claseDeclarante,
                                 Visibilidad visibilidad, ASTNode donde) {
        if (puedeAcceder(visibilidad, claseDeclarante)) return;

        String origen = claseActual != null
                ? "la clase '" + claseActual + "'"
                : "el programa principal del paquete " + nombrePaquete(paqueteActual);
        String regla = switch (visibilidad) {
            case PRIVATE -> "solo es accesible dentro de la clase '" + claseDeclarante + "'";
            case PROTECTED -> "solo es accesible desde el paquete "
                    + nombrePaquete(contexto.paquetesPorClase.getOrDefault(claseDeclarante, ""))
                    + " o desde subclases de '" + claseDeclarante + "'";
            default -> "solo es accesible desde clases del paquete "
                    + nombrePaquete(contexto.paquetesPorClase.getOrDefault(claseDeclarante, ""));
        };
        String nombreCompleto = tipoMiembro.equals("constructor") ? "de '" + claseDeclarante + "'"
                : "'" + nombre + "' de '" + claseDeclarante + "'";
        throw new SemanticException("No se puede acceder al " + tipoMiembro + " " + nombreCompleto
                + " desde " + origen + ": es " + visibilidad.etiqueta() + " y " + regla
                + " (línea " + donde.getLine() + ")", donde.getLine(), donde.getColumn());
    }

    // ================= DECLARACIONES =================

    @Override
    public Type visit(ProgramNode n) {
        String paqueteAnterior = paqueteActual;
        paqueteActual = n.paquete == null ? "" : n.paquete;
        try {
            return visitarPrograma(n);
        } finally {
            paqueteActual = paqueteAnterior;
        }
    }

    private Type visitarPrograma(ProgramNode n) {
        for (var d : n.declaraciones) {
            if (d instanceof FunctionDeclNode f && !f.esMetodo) {
                if (contexto.funciones.containsKey(f.nombre)) {
                    throw new SemanticException("La función '" + f.nombre
                            + "' ya está definida (línea " + f.getLine() + ")");
                }
                contexto.funciones.put(f.nombre, f);
            }
        }

        for (var imp : n.imports) imp.accept(this);

        for (var d : n.declaraciones) {
            if (d instanceof StructDeclNode || d instanceof ClassDeclNode) d.accept(this);
        }
        for (var d : n.declaraciones) {
            if (d instanceof VarDeclNode) d.accept(this);
        }
        for (var d : n.declaraciones) {
            if (d instanceof FunctionDeclNode) d.accept(this);
        }
        return Type.VOID;
    }

    /*@Override
    public Type visit(ImportNode n) {
        String[] partes = n.ruta.split("\\.");
        if (partes.length < 2) return Type.VOID;

        String posibleNombreTipo = partes[partes.length - 2];
        boolean existe = (n.tipo == ImportNode.TipoImport.CLASE_Z)
                ? contexto.classes.containsKey(posibleNombreTipo)
                : (contexto.structs.containsKey(posibleNombreTipo) || contexto.funciones.containsKey(posibleNombreTipo));

        if (!existe) {
            throw new SemanticException("No se encontró nada registrado para el import '" + n.ruta
                    + "' (se esperaba encontrar '" + posibleNombreTipo + "'). "
                    + "Verifica que ese archivo se haya procesado antes (línea " + n.getLine() + ")");
        }
        return Type.VOID;
    }*/

    @Override
    public Type visit(ImportNode n) {
        String[] partes = n.ruta.split("\\.");
        if (partes.length < 2) return Type.VOID;

        if (n.tipo != ImportNode.TipoImport.CLASE_Z) return Type.VOID;

        String posibleNombreTipo = partes[partes.length - 2];
        boolean existe = contexto.classes.containsKey(posibleNombreTipo);

        if (!existe) {
            throw new SemanticException("No se encontró nada registrado para el import '" + n.ruta
                    + "' (se esperaba encontrar '" + posibleNombreTipo + "'). "
                    + "Verifica que ese archivo se haya procesado antes (línea " + n.getLine() + ")");
        }
        return Type.VOID;
    }

    @Override
    public Type visit(StructDeclNode n) {
        if (contexto.funciones.containsKey(n.nombre)) {
            throw new SemanticException("El nombre '" + n.nombre + "' ya está usado por una función importada (línea " + n.getLine() + ")");
        }
        contexto.structs.put(n.nombre, n);

        Map<String, ResolvedType> layout = new LinkedHashMap<>();
        Map<String, List<Integer>> dimensiones = new LinkedHashMap<>();
        for (FieldNode campo : n.campos) {
            campo.accept(this);
            layout.put(campo.nombre, new ResolvedType(campo.tipoResuelto, campo.tipoUsuarioResuelto));
            if (!campo.dimensionesArreglo.isEmpty()) dimensiones.put(campo.nombre, campo.dimensionesArreglo);
        }
        contexto.layoutsEstructuras.put(n.nombre, layout);
        if (!dimensiones.isEmpty()) contexto.dimensionesCamposEstructuras.put(n.nombre, dimensiones);
        return Type.VOID;
    }

    @Override
    public Type visit(ClassDeclNode n) {
        if (contexto.funciones.containsKey(n.nombre)) {
            throw new SemanticException("El nombre '" + n.nombre + "' ya está usado por una función importada (línea " + n.getLine() + ")");
        }
        contexto.classes.put(n.nombre, n);
        contexto.paquetesPorClase.put(n.nombre, paqueteActual);

        Map<String, ResolvedType> layout = new LinkedHashMap<>();
        Map<String, List<Integer>> dimensiones = new LinkedHashMap<>();
        for (FieldNode atributo : n.atributos) {
            atributo.accept(this);
            layout.put(atributo.nombre, new ResolvedType(atributo.tipoResuelto, atributo.tipoUsuarioResuelto));
            if (!atributo.dimensionesArreglo.isEmpty()) dimensiones.put(atributo.nombre, atributo.dimensionesArreglo);
        }
        contexto.layoutsClases.put(n.nombre, layout);
        if (!dimensiones.isEmpty()) contexto.dimensionesCamposClases.put(n.nombre, dimensiones);

        Map<String, FunctionDeclNode> metodos = new LinkedHashMap<>();
        for (FunctionDeclNode m : n.metodos) metodos.put(m.nombre, m);
        contexto.metodosPorClase.put(n.nombre, metodos);

        // Sobrecarga de métodos: se guardan todas las firmas; no pueden repetirse los mismos parámetros.
        Map<String, List<FunctionDeclNode>> sobrecargas = new LinkedHashMap<>();
        for (FunctionDeclNode m : n.metodos) {
            List<FunctionDeclNode> lista = sobrecargas.computeIfAbsent(m.nombre, k -> new ArrayList<>());
            for (FunctionDeclNode otro : lista) {
                if (otro.nombreCodigo().equals(m.nombreCodigo())) {
                    throw new SemanticException("El método '" + m.nombre + "' ya está definido con los mismos parámetros en la clase '"
                            + n.nombre + "' (línea " + m.getLine() + ")");
                }
            }
            lista.add(m);
        }
        contexto.sobrecargasPorClase.put(n.nombre, sobrecargas);
        contexto.constructoresPorClase.put(n.nombre, n.constructores);

        for (var ctor : n.constructores) ctor.accept(this);
        for (var m : n.metodos) m.accept(this);
        return Type.VOID;
    }

    @Override
    public Type visit(FunctionDeclNode n) {
        ResolvedType retornoAnterior = tipoRetornoFuncionActual;
        String claseAnterior = claseActual;
        tipoRetornoFuncionActual = resolverTipoDeclarado(n.tipoRetorno, n.getLine());

        abrirScope();
        if (n.esMetodo && n.claseDuena != null) {
            claseActual = n.claseDuena;
            scopeActual.declarar("this", Type.CLASE, n.claseDuena, n.getLine(), n.getColumn());
            precargarAtributosDeClase(n.claseDuena);
        }
        for (ParamNode p : n.parametros) p.accept(this);
        for (Statement s : n.cuerpo) s.accept(this);
        cerrarScope();

        tipoRetornoFuncionActual = retornoAnterior;
        claseActual = claseAnterior;
        return Type.VOID;
    }

    @Override
    public Type visit(ConstructorDeclNode n) {
        String claseAnterior = claseActual;
        claseActual = n.claseDuena;

        abrirScope();
        scopeActual.declarar("this", Type.CLASE, n.claseDuena, n.getLine(), n.getColumn());
        precargarAtributosDeClase(n.claseDuena);
        for (ParamNode p : n.parametros) p.accept(this);
        for (Statement s : n.cuerpo) s.accept(this);
        cerrarScope();

        claseActual = claseAnterior;
        return Type.VOID;
    }

    @Override
    public Type visit(FieldNode n) {
        ResolvedType rt = resolverTipoDeclarado(n.tipo, n.getLine());
        n.tipoResuelto = rt.tipo();
        n.tipoUsuarioResuelto = rt.tipoUsuario();
        return rt.tipo();
    }

    @Override
    public Type visit(ParamNode n) {
        ResolvedType rt = resolverTipoDeclarado(n.tipo, n.getLine());
        n.tipoResuelto = rt.tipo();
        n.tipoUsuarioResuelto = rt.tipoUsuario();

        if (scopeActual.existeEnScopeActual(n.nombre)) {
            throw new SemanticException("Parámetro ya declarado en este ámbito: " + n.nombre  + " (línea " + n.getLine() + ")");
        }
        if (contexto.funciones.containsKey(n.nombre)) {
            throw new SemanticException("El nombre '" + n.nombre + "' ya está usado por una función importada (línea " + n.getLine() + ")");
        }

        if (n.modo == ParamNode.ModoPaso.REFERENCIA_ARREGLO && n.dimensionesArreglo.size() > 1) {
            for (int i = 1; i < n.dimensionesArreglo.size(); i++) {
                if (n.dimensionesArreglo.get(i) < 0) {
                    throw new SemanticException("El parámetro '" + n.nombre
                            + "' es un arreglo de " + n.dimensionesArreglo.size()
                            + " dimensiones por referencia, pero no se conoce el tamaño de sus"
                            + " dimensiones internas (línea " + n.getLine()
                            + "). Indícalo en la propia declaración del parámetro"
                            + " (como en Y?: '[][3] entero " + n.nombre + "').");
                }
            }
        }

        scopeActual.declarar(n.nombre, rt.tipo(), rt.tipoUsuario(), n.getLine(), n.getColumn());
        return rt.tipo();
    }

    @Override
    public Type visit(VarDeclNode n) {
        // 1. Resolver el tipo declarado de la variable (p. ej. "Punto" -> ESTRUCTURA)
        ResolvedType rt = resolverTipoDeclarado(n.tipo, n.getLine());
        n.tipoResuelto = rt.tipo();
        n.tipoUsuarioResuelto = rt.tipoUsuario();

        // 2. Validar que la variable no esté duplicada en el ámbito actual
        if (scopeActual.existeEnScopeActual(n.nombre)) {
            throw new SemanticException("Variable ya declarada en este ámbito: " + n.nombre
                    + " (línea " + n.getLine() + ")");
        }

        if (contexto.funciones.containsKey(n.nombre)) {
            throw new SemanticException("El nombre '" + n.nombre
                    + "' ya está usado por una función importada (línea " + n.getLine() + ")");
        }

        // 3. Evaluar el inicializador si existe
        if (n.inicializador != null) {
            ASTNode initNode = (ASTNode) n.inicializador;

            // Para inicializaciones de estructuras con sintaxis de llaves {10, 20}
            if (rt.tipo() == Type.ESTRUCTURA && initNode instanceof ArrayLiteralNode litStruct) {
                litStruct.tipoUsuarioResuelto = n.tipo;
                litStruct.tipoResuelto = Type.ESTRUCTURA;
            }

            if (initNode instanceof ReadNode lectura && lectura.variableDestino == null) lectura.tipoEsperado = rt.tipo();
            Type tipoInit = initNode.accept(this);
            String tipoUsuarioInit = tipoUsuarioDe(initNode);

            // Si es una estructura asignada con llaves, forzamos la coincidencia de tipo de usuario
            if (rt.tipo() == Type.ESTRUCTURA && (initNode instanceof ArrayLiteralNode || tipoInit == Type.ESTRUCTURA)) {
                tipoInit = Type.ESTRUCTURA;
                if (tipoUsuarioInit == null) {
                    tipoUsuarioInit = n.tipo;
                }
            }

            // Validar compatibilidad entre el tipo declarado y la expresión asignada
            if (!TypeCoercionTable.compatibles(rt.tipo(), rt.tipoUsuario(), tipoInit, tipoUsuarioInit)) {
                throw new SemanticException("No se puede asignar " + etiquetaTipo(tipoInit, tipoUsuarioInit)
                        + " a " + rt.etiqueta() + " en la variable '" + n.nombre
                        + "' (línea " + n.getLine() + ")");
            }

            // int[][] m = new int[3][3];  -> con tamaños constantes, las dimensiones de la variable salen del 'new'.
            // Si algún tamaño es una expresión (new int[n]) la variable queda con tamaño en tiempo de ejecución (-1).
            if (initNode instanceof NewArrayNode nuevoArr && !n.dimensionesArreglo.isEmpty()
                    && n.dimensionesArreglo.contains(-1) && n.dimensionesArreglo.size() == nuevoArr.dimensiones.size()) {
                List<Integer> constantes = new ArrayList<>();
                for (Expression d : nuevoArr.dimensiones) {
                    if (d instanceof LiteralNode lit && lit.valorCrudo.matches("\\d+")) constantes.add(Integer.parseInt(lit.valorCrudo));
                    else { constantes = null; break; }
                }
                if (constantes != null) n.dimensionesArreglo = constantes;
            }

            // int[][] m = new int[3][3];  -> las dimensiones se toman del 'new' cuando se declaró sin tamaño
            if (initNode instanceof ArrayLiteralNode literalNew && literalNew.dimensionesNew != null
                    && !n.dimensionesArreglo.isEmpty() && n.dimensionesArreglo.contains(-1)
                    && n.dimensionesArreglo.size() == literalNew.dimensionesNew.size()) {
                n.dimensionesArreglo = new ArrayList<>(literalNew.dimensionesNew);
            }

            // int[] numeros = {10, 20, 30};  -> el tamaño se toma del literal cuando se declaró sin tamaño ('[]')
            if (initNode instanceof ArrayLiteralNode literalInferido && n.dimensionesArreglo.size() == 1
                    && n.dimensionesArreglo.get(0) < 0 && rt.tipo() != Type.ESTRUCTURA) {
                n.dimensionesArreglo = new ArrayList<>(List.of(contarValoresPlanos(literalInferido)));
            }

            // Para arreglos de N dimensiones (series matriz[2][3] : ... {{...},{...}}),
            // el literal se aplana a un solo bloque contiguo (ver ExpressionGenerator),
            // así que su cantidad de valores debe coincidir con el total de posiciones
            // declaradas (2*3 = 6). Si no, el arreglo queda con memoria mal repartida.
            if (!n.dimensionesArreglo.isEmpty() && initNode instanceof ArrayLiteralNode literalArreglo
                    && rt.tipo() != Type.ESTRUCTURA) {
                int total = 1;
                for (int d : n.dimensionesArreglo) total *= d;
                int planos = contarValoresPlanos(literalArreglo);
                if (planos != total) {
                    throw new SemanticException("El arreglo '" + n.nombre + "' declara " + total
                            + " posición(es) (" + n.dimensionesArreglo + ") pero el literal trae "
                            + planos + " valor(es) (línea " + n.getLine() + ")");
                }
            }
        }

        // 4. Registrar la variable en la tabla de símbolos
        scopeActual.declarar(n.nombre, rt.tipo(), rt.tipoUsuario(), n.getLine(), n.getColumn());
        return Type.VOID;
    }

    // ================= SENTENCIAS =================

    @Override
    public Type visit(AssignNode n) {
        Type tipoDestino = n.destino.accept(this);
        String tipoUsuarioDestino = tipoUsuarioDe(n.destino);
        if (n.valor instanceof ReadNode lectura && lectura.variableDestino == null) lectura.tipoEsperado = tipoDestino;
        Type tipoValor = n.valor.accept(this);
        String tipoUsuarioValor = tipoUsuarioDe(n.valor);

        if (!n.operador.equals("=")) {
            Type tipoOperacion = n.operador.startsWith("+")
                    ? TypeCoercionTable.resultadoSuma(tipoDestino, tipoValor)
                    : TypeCoercionTable.resultadoAritmetico(tipoDestino, tipoValor);
            if (tipoOperacion == Type.ERROR) {
                throw new SemanticException("Operación inválida: " + tipoDestino + " " + n.operador
                        + " " + tipoValor + " (línea " + n.getLine() + ")");
            }
            tipoValor = tipoOperacion;
            tipoUsuarioValor = null;
        }

        if (!TypeCoercionTable.compatibles(tipoDestino, tipoUsuarioDestino, tipoValor, tipoUsuarioValor)) {
            throw new SemanticException("Asignación incompatible: " + etiquetaTipo(tipoValor, tipoUsuarioValor)
                    + " a " + etiquetaTipo(tipoDestino, tipoUsuarioDestino) + " (línea " + n.getLine() + ")");
        }
        return Type.VOID;
    }

    @Override
    public Type visit(IncrDecrNode n) {
        Symbol s = scopeActual.resolver(n.nombreVariable);
        if (s == null) throw new SemanticException("Variable no declarada: " + n.nombreVariable
                + " (línea " + n.getLine() + ")");
        if (s.tipo != Type.ENTERO && s.tipo != Type.FLOTANTE) {
            throw new SemanticException("'" + n.operador + "' requiere un tipo numérico, no " + s.tipo
                    + " (línea " + n.getLine() + ")");
        }
        return Type.VOID;
    }

    @Override
    public Type visit(IfNode n) {
        for (IfNode.Branch rama : n.ramas) {
            Type tipoCond = rama.condicion.accept(this);
            if (tipoCond != Type.BOOL) {
                throw new SemanticException("La condición del 'si' debe ser booleana, no " + tipoCond
                        + " (línea " + n.getLine() + ")");
            }
            visitarCuerpo(rama.cuerpo);
        }
        if (n.ramaContrario != null) visitarCuerpo(n.ramaContrario);
        return Type.VOID;
    }

    @Override
    public Type visit(SwitchNode n) {
        n.selector.accept(this);
        profundidadCiclo++;
        for (CaseNode c : n.casos) c.accept(this);
        profundidadCiclo--;
        return Type.VOID;
    }

    @Override
    public Type visit(CaseNode n) {
        visitarCuerpo(n.cuerpo);
        return Type.VOID;
    }

    @Override
    public Type visit(WhileNode n) {
        Type tipoCond = n.condicion.accept(this);
        if (tipoCond != Type.BOOL) {
            throw new SemanticException("La condición del ciclo debe ser booleana, no " + tipoCond
                    + " (línea " + n.getLine() + ")");
        }
        profundidadCiclo++;
        visitarCuerpo(n.cuerpo);
        profundidadCiclo--;
        return Type.VOID;
    }

    @Override
    public Type visit(DoWhileNode n) {
        profundidadCiclo++;
        visitarCuerpo(n.cuerpo);
        profundidadCiclo--;
        Type tipoCond = n.condicion.accept(this);
        if (tipoCond != Type.BOOL) {
            throw new SemanticException("La condición del ciclo debe ser booleana, no " + tipoCond
                    + " (línea " + n.getLine() + ")");
        }
        return Type.VOID;
    }

    @Override
    public Type visit(ForNode n) {
        abrirScope();
        if (n.inicializacion != null) n.inicializacion.accept(this);
        if (n.condicion != null) {
            Type tipoCond = n.condicion.accept(this);
            if (tipoCond != Type.BOOL) {
                throw new SemanticException("La condición del 'para/for' debe ser booleana, no " + tipoCond
                        + " (línea " + n.getLine() + ")");
            }
        }
        if (n.actualizacion != null) n.actualizacion.accept(this);

        profundidadCiclo++;
        for (Statement s : n.cuerpo) s.accept(this);
        profundidadCiclo--;

        cerrarScope();
        return Type.VOID;
    }

    @Override
    public Type visit(BreakNode n) {
        if (profundidadCiclo == 0) {
            throw new SemanticException("'romper/break/interrumpe' fuera de un ciclo (línea " + n.getLine() + ")");
        }
        return Type.VOID;
    }

    @Override
    public Type visit(ContinueNode n) {
        if (profundidadCiclo == 0) {
            throw new SemanticException("'continuar/continue/perge' fuera de un ciclo (línea " + n.getLine() + ")");
        }
        return Type.VOID;
    }

    @Override
    public Type visit(ReturnNode n) {
        Type tipoValor = n.valor == null ? Type.VOID : n.valor.accept(this);
        String tipoUsuarioValor = n.valor == null ? null : tipoUsuarioDe(n.valor);

        if (tipoRetornoFuncionActual != null) {
            if (!TypeCoercionTable.compatibles(tipoRetornoFuncionActual.tipo(), tipoRetornoFuncionActual.tipoUsuario(), tipoValor, tipoUsuarioValor)) {
                throw new SemanticException("La función debe retornar " + tipoRetornoFuncionActual.etiqueta()
                        + " pero se retornó " + etiquetaTipo(tipoValor, tipoUsuarioValor) + " (línea " + n.getLine() + ")");
            }
        }
        return Type.VOID;
    }

    @Override
    public Type visit(PrintNode n) {
        for (var arg : n.argumentos) arg.accept(this);
        return Type.VOID;
    }

    @Override
    public Type visit(ReadNode n) {
        Type tipo;
        if (n.variableDestino != null) {
            Symbol s = scopeActual.resolver(n.variableDestino);
            if (s == null) throw new SemanticException("Variable no declarada: " + n.variableDestino
                    + " (línea " + n.getLine() + ")");
            tipo = s.tipo;
        } else if (n.tipoEsperado == Type.ENTERO || n.tipoEsperado == Type.FLOTANTE || n.tipoEsperado == Type.CARACTER
                || n.tipoEsperado == Type.BOOL || n.tipoEsperado == Type.CADENA) {
            tipo = n.tipoEsperado;      // leer() se lee con el tipo de la variable que lo recibe
        } else {
            tipo = Type.CADENA;
        }
        n.tipoResuelto = tipo;
        return tipo;
    }

    @Override
    public Type visit(ExprStatementNode n) {
        n.expresion.accept(this);
        return Type.VOID;
    }

    // ================= EXPRESIONES =================

    @Override
    public Type visit(BinaryExprNode n) {
        Type izq = n.izquierda.accept(this);
        Type der = n.derecha.accept(this);
        Type resultado;

        switch (n.operador) {
            case "&&": case "||":
                if (izq != Type.BOOL || der != Type.BOOL) {
                    throw new SemanticException("'" + n.operador + "' requiere operandos booleanos (línea "
                            + n.getLine() + ")");
                }
                resultado = Type.BOOL;
                break;

            case "==": case "!=": {
                boolean izqEsObjeto = izq == Type.ESTRUCTURA || izq == Type.CLASE;
                boolean derEsObjeto = der == Type.ESTRUCTURA || der == Type.CLASE;
                boolean izqEsNulo = izq == Type.NULO;
                boolean derEsNulo = der == Type.NULO;

                if (izqEsObjeto || derEsObjeto || izqEsNulo || derEsNulo) {
                    String tuIzq = tipoUsuarioDe(n.izquierda);
                    String tuDer = tipoUsuarioDe(n.derecha);
                    boolean valido = (izqEsNulo && derEsObjeto) || (derEsNulo && izqEsObjeto)
                            || (izqEsObjeto && derEsObjeto && izq == der && Objects.equals(tuIzq, tuDer));
                    if (!valido) {
                        throw new SemanticException("No se puede comparar " + etiquetaTipo(izq, tuIzq)
                                + " con " + etiquetaTipo(der, tuDer) + " (línea " + n.getLine() + ")");
                    }
                } else if (TypeCoercionTable.resultado(izq, der) == Type.ERROR) {
                    throw new SemanticException("No se puede comparar " + izq + " con " + der
                            + " (línea " + n.getLine() + ")");
                }
                resultado = Type.BOOL;
                break;
            }

            case "<": case ">": case "<=": case ">=":
                if (TypeCoercionTable.resultado(izq, der) == Type.ERROR) {
                    throw new SemanticException("No se puede comparar " + izq + " con " + der
                            + " (línea " + n.getLine() + ")");
                }
                resultado = Type.BOOL;
                break;

            case "+": // suma numérica o concatenación, según los tipos
                resultado = TypeCoercionTable.resultadoSuma(izq, der);
                if (resultado == Type.ERROR) {
                    throw new SemanticException("Operación inválida: " + izq + " + " + der
                            + " (línea " + n.getLine() + ")");
                }
                break;

            default: // - * / %
                resultado = TypeCoercionTable.resultadoAritmetico(izq, der);
                if (resultado == Type.ERROR) {
                    throw new SemanticException("Operación inválida: " + izq + " " + n.operador + " " + der
                            + " (línea " + n.getLine() + ")");
                }
        }

        n.tipoResuelto = resultado;
        return resultado;
    }

    @Override
    public Type visit(UnaryExprNode n) {
        Type tipoOperando = n.operando.accept(this);
        Type resultado;
        if (n.operador.equals("!")) {
            if (tipoOperando != Type.BOOL) throw new SemanticException("'!' requiere un booleano, no " + tipoOperando
                    + " (línea " + n.getLine() + ")");
            resultado = Type.BOOL;
        } else {
            if (tipoOperando != Type.ENTERO && tipoOperando != Type.FLOTANTE) {
                throw new SemanticException("'-' unario requiere un tipo numérico, no " + tipoOperando
                        + " (línea " + n.getLine() + ")");
            }
            resultado = tipoOperando;
        }
        n.tipoResuelto = resultado;
        return resultado;
    }

    @Override
    public Type visit(TernaryExprNode n) {
        Type tipoCond = n.condicion.accept(this);
        if (tipoCond != Type.BOOL) throw new SemanticException("La condición del ternario debe ser booleana"
                + " (línea " + n.getLine() + ")");
        Type tipoV = n.siVerdadero.accept(this);
        Type tipoF = n.siFalso.accept(this);
        Type resultado = TypeCoercionTable.resultado(tipoV, tipoF);
        if (resultado == Type.ERROR) {
            throw new SemanticException("Las dos ramas del ternario deben ser compatibles: "
                    + tipoV + " vs " + tipoF + " (línea " + n.getLine() + ")");
        }
        n.tipoResuelto = resultado;
        return resultado;
    }

    @Override
    public Type visit(LiteralNode n) {
        String v = n.valorCrudo;
        Type resultado;
        if (v.matches("\\d+")) resultado = Type.ENTERO;
        else if (v.matches("\\d+\\.\\d+")) resultado = Type.FLOTANTE;
        else if (v.startsWith("\"")) resultado = Type.CADENA;
        else if (v.startsWith("'")) resultado = Type.CARACTER;
        else if (v.equals("verdadero") || v.equals("true") || v.equals("verum")
                || v.equals("falso") || v.equals("false") || v.equals("falsus")) resultado = Type.BOOL;
        else if (v.equals("null")) resultado = Type.NULO;
        else resultado = Type.ERROR;

        n.tipoResuelto = resultado;
        return resultado;
    }

    @Override
    public Type visit(IdentifierNode n) {
        Symbol s = scopeActual.resolver(n.nombre);
        if (s == null) throw new SemanticException("Variable no declarada: " + n.nombre
                + " (línea " + n.getLine() + ")");
        n.tipoResuelto = s.tipo;
        n.tipoUsuarioResuelto = s.tipoUsuario;
        return s.tipo;
    }

    @Override
    public Type visit(PostfixExprNode n) {
        boolean esLlamadaAFuncionLibre = n.base instanceof IdentifierNode idBase
                && !n.sufijos.isEmpty() && n.sufijos.get(0) instanceof CallSuffix
                && contexto.funciones.containsKey(idBase.nombre);

        boolean esLlamadaAMetodoImplicito = !esLlamadaAFuncionLibre
                && n.base instanceof IdentifierNode idBase2
                && !n.sufijos.isEmpty() && n.sufijos.get(0) instanceof CallSuffix
                && claseActual != null
                && tieneMetodo(claseActual, idBase2.nombre);

        Type tipoActual;
        String tipoUsuarioActual;
        if (esLlamadaAFuncionLibre) {
            tipoActual = Type.ERROR;
            tipoUsuarioActual = null;
        } else if (esLlamadaAMetodoImplicito) {
            tipoActual = Type.CLASE;
            tipoUsuarioActual = claseActual;
        } else {
            tipoActual = n.base.accept(this);
            tipoUsuarioActual = tipoUsuarioDe(n.base);
        }

        List<Suffix> sufijos = n.sufijos;
        for (int i = 0; i < sufijos.size(); i++) {
            Suffix s = sufijos.get(i);

            if (s instanceof FieldSuffix fs) {
                boolean esNombreDeMetodo = (i + 1 < sufijos.size()) && (sufijos.get(i + 1) instanceof CallSuffix);
                if (esNombreDeMetodo) {
                    continue;
                }

                Map<String, ResolvedType> layout = obtenerLayout(tipoActual, tipoUsuarioActual);
                if (layout == null) {
                    throw new SemanticException("No se puede acceder a '." + fs.nombreCampo
                            + "' porque '" + etiquetaTipo(tipoActual, tipoUsuarioActual)
                            + "' no es una estructura ni una clase conocida (línea " + n.getLine() + ")");
                }
                ResolvedType campo = layout.get(fs.nombreCampo);
                if (campo == null) {
                    throw new SemanticException("'" + tipoUsuarioActual + "' no tiene un campo llamado '"
                            + fs.nombreCampo + "' (línea " + n.getLine() + ")");
                }
                if (tipoActual == Type.CLASE) {
                    AtributoDeclarado decl = buscarAtributo(tipoUsuarioActual, fs.nombreCampo);
                    if (decl != null) {
                        verificarAcceso("atributo", fs.nombreCampo, decl.claseDeclarante(), decl.nodo().visibilidad, n);
                    }
                }
                tipoActual = campo.tipo();
                tipoUsuarioActual = campo.tipoUsuario();

            } else if (s instanceof IndexSuffix idx) {
                Type tipoIndice = idx.indice.accept(this);
                if (tipoIndice != Type.ENTERO) {
                    throw new SemanticException("El índice de un arreglo debe ser entero, no " + tipoIndice
                            + " (línea " + n.getLine() + ")");
                }

            } else if (s instanceof CallSuffix call) {
                FieldSuffix nombreMetodo = (i > 0 && sufijos.get(i - 1) instanceof FieldSuffix fsPrevio)
                        ? fsPrevio : null;

                if (nombreMetodo != null && tipoUsuarioActual != null
                        && contexto.metodosPorClase.containsKey(tipoUsuarioActual)) {

                    FunctionDeclNode metodo = resolverMetodo(tipoUsuarioActual, nombreMetodo.nombreCampo, call, n.getLine());
                    if (metodo == null) {
                        throw new SemanticException("La clase '" + tipoUsuarioActual + "' no tiene un método '"
                                + nombreMetodo.nombreCampo + "' (línea " + n.getLine() + ")");
                    }

                    verificarAcceso("método", metodo.nombre, metodo.claseDuena, metodo.visibilidad, n);
                    validarParametros(metodo.parametros, call.argumentos, tipoUsuarioActual + "." + nombreMetodo.nombreCampo, n.getLine());
                    ResolvedType rtRetorno = resolverTipoDeclarado(metodo.tipoRetorno, n.getLine());
                    tipoActual = rtRetorno.tipo();
                    tipoUsuarioActual = rtRetorno.tipoUsuario();

                } else if (esLlamadaAFuncionLibre && i == 0) {
                    String nombreFuncion = ((IdentifierNode) n.base).nombre;
                    FunctionDeclNode f = contexto.funciones.get(nombreFuncion);

                    validarParametros(f.parametros, call.argumentos, nombreFuncion, n.getLine());
                    ResolvedType rtRetorno = resolverTipoDeclarado(f.tipoRetorno, n.getLine());
                    tipoActual = rtRetorno.tipo();
                    tipoUsuarioActual = rtRetorno.tipoUsuario();

                } else if (esLlamadaAMetodoImplicito && i == 0) {
                    String nombreMetodoImplicito = ((IdentifierNode) n.base).nombre;
                    FunctionDeclNode metodo = resolverMetodo(claseActual, nombreMetodoImplicito, call, n.getLine());

                    verificarAcceso("método", metodo.nombre, metodo.claseDuena, metodo.visibilidad, n);
                    validarParametros(metodo.parametros, call.argumentos,
                            claseActual + "." + nombreMetodoImplicito, n.getLine());
                    ResolvedType rtRetorno = resolverTipoDeclarado(metodo.tipoRetorno, n.getLine());
                    tipoActual = rtRetorno.tipo();
                    tipoUsuarioActual = rtRetorno.tipoUsuario();

                } else {
                    throw new SemanticException("No se pudo resolver la llamada (línea " + n.getLine() + ")");
                }
            }
        }

        n.tipoResuelto = tipoActual;
        n.tipoUsuarioResuelto = tipoUsuarioActual;
        return tipoActual;
    }

    @Override
    public Type visit(NewObjectNode n) {
        List<Type> tiposArgs = new ArrayList<>();
        List<String> tiposUsuarioArgs = new ArrayList<>();
        for (var arg : n.argumentos) {
            tiposArgs.add(arg.accept(this));
            tiposUsuarioArgs.add(tipoUsuarioDe(arg));
        }

        if (!contexto.classes.containsKey(n.nombreClase)) {
            throw new SemanticException("Clase no encontrada: '" + n.nombreClase
                    + "'. ¿Falta importar el archivo .z que la define? (línea " + n.getLine() + ")");
        }

        List<ConstructorDeclNode> constructores = contexto.constructoresPorClase.getOrDefault(n.nombreClase, List.of());
        boolean coincide = constructores.isEmpty() && n.argumentos.isEmpty();
        for (ConstructorDeclNode ctor : constructores) {
            if (coincideFirma(ctor.parametros, tiposArgs, tiposUsuarioArgs, n.getLine())) {
                coincide = true;
                verificarAcceso("constructor", n.nombreClase, n.nombreClase, ctor.visibilidad, n);
                break;
            }
        }
        if (!coincide) {
            throw new SemanticException("Ningún constructor de '" + n.nombreClase
                    + "' coincide con los " + tiposArgs.size() + " argumento(s) dado(s) (línea " + n.getLine() + ")");
        }

        n.tipoResuelto = Type.CLASE;
        n.tipoUsuarioResuelto = n.nombreClase;
        return Type.CLASE;
    }

    private boolean coincideFirma(List<ParamNode> parametros, List<Type> tiposArgs, List<String> tiposUsuarioArgs, int linea) {
        if (parametros.size() != tiposArgs.size()) return false;
        for (int i = 0; i < parametros.size(); i++) {
            ResolvedType rtParam = resolverTipoDeclarado(parametros.get(i).tipo, linea);
            if (!TypeCoercionTable.compatibles(rtParam.tipo(), rtParam.tipoUsuario(), tiposArgs.get(i), tiposUsuarioArgs.get(i))) {
                return false;
            }
        }
        return true;
    }

    @Override
    public Type visit(NewArrayNode n) {
        for (Expression d : n.dimensiones) {
            Type t = d.accept(this);
            if (t != Type.ENTERO) {
                throw new SemanticException("El tamaño de un arreglo debe ser entero, no " + t + " (línea " + n.getLine() + ")");
            }
        }
        ResolvedType rt = resolverTipoDeclarado(n.tipoElemento, n.getLine());
        n.tipoResuelto = rt.tipo();
        n.tipoUsuarioResuelto = rt.tipoUsuario();
        return rt.tipo();
    }

    @Override
    public Type visit(ArrayLiteralNode n) {
        if (n.tipoResuelto == Type.ESTRUCTURA && n.tipoUsuarioResuelto != null) {
            return verificarLiteralEstructura(n);
        }

        Type tipoComun = null;
        for (var val : n.valores) {
            Type t = val.accept(this);
            if (tipoComun == null) tipoComun = t;
            else if (TypeCoercionTable.resultado(tipoComun, t) == Type.ERROR) {
                throw new SemanticException("Elementos de arreglo con tipos incompatibles: "
                        + tipoComun + " y " + t + " (línea " + n.getLine() + ")");
            }
        }
        Type resultado = tipoComun == null ? Type.ERROR : tipoComun;
        n.tipoResuelto = resultado;
        return resultado;
    }

    /**
     * Valida "Direccion {"Calle Real", 42}" contra el layout real de la estructura:
     * misma cantidad de valores que de campos, y cada valor compatible con el tipo
     * de su campo (en orden de declaración). Si un campo es a su vez una estructura
     * y el valor es otro '{...}', se etiqueta y valida recursivamente, lo que permite
     * anidar: Persona {"Valeria", 25, {"Avenida Central", 500}}.
     */
    private Type verificarLiteralEstructura(ArrayLiteralNode n) {
        String nombreEstructura = n.tipoUsuarioResuelto;
        Map<String, ResolvedType> layout = contexto.layoutsEstructuras.get(nombreEstructura);
        if (layout == null) {
            throw new SemanticException("Estructura no encontrada: '" + nombreEstructura
                    + "' (línea " + n.getLine() + ")");
        }

        List<Map.Entry<String, ResolvedType>> campos = new ArrayList<>(layout.entrySet());
        if (campos.size() != n.valores.size()) {
            throw new SemanticException("La estructura '" + nombreEstructura + "' tiene " + campos.size()
                    + " campo(s) pero el literal trae " + n.valores.size() + " valor(es) (línea " + n.getLine() + ")");
        }

        for (int i = 0; i < campos.size(); i++) {
            String nombreCampo = campos.get(i).getKey();
            ResolvedType tipoCampo = campos.get(i).getValue();
            Expression valor = n.valores.get(i);

            if (tipoCampo.tipo() == Type.ESTRUCTURA && valor instanceof ArrayLiteralNode anidado) {
                anidado.tipoResuelto = Type.ESTRUCTURA;
                anidado.tipoUsuarioResuelto = tipoCampo.tipoUsuario();
            }

            Type tipoValor = ((ASTNode) valor).accept(this);
            String tipoUsuarioValor = tipoUsuarioDe(valor);
            if (tipoCampo.tipo() == Type.ESTRUCTURA && (valor instanceof ArrayLiteralNode || tipoValor == Type.ESTRUCTURA)) {
                tipoValor = Type.ESTRUCTURA;
                if (tipoUsuarioValor == null) tipoUsuarioValor = tipoCampo.tipoUsuario();
            }

            if (!TypeCoercionTable.compatibles(tipoCampo.tipo(), tipoCampo.tipoUsuario(), tipoValor, tipoUsuarioValor)) {
                throw new SemanticException("El campo '" + nombreCampo + "' de '" + nombreEstructura
                        + "' espera " + etiquetaTipo(tipoCampo.tipo(), tipoCampo.tipoUsuario()) + ", se dio "
                        + etiquetaTipo(tipoValor, tipoUsuarioValor) + " (línea " + n.getLine() + ")");
            }
        }

        n.tipoResuelto = Type.ESTRUCTURA;
        return Type.ESTRUCTURA;
    }
}