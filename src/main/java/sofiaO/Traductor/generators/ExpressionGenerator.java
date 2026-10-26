package sofiaO.Traductor.generators;

import sofiaO.ast.ASTNode;
import sofiaO.ast.Expression;
import sofiaO.ast.declaracion.FunctionDeclNode;
import sofiaO.ast.expresion.*;
import sofiaO.semantico.ResolvedType;
import sofiaO.util.Type;

import java.util.ArrayList;
import java.util.List;

/** Genera cuartetas para expresiones: operadores, literales, accesos, llamadas, objetos nuevos. */
public class ExpressionGenerator {

    private final GeneratorContext ctx;

    public ExpressionGenerator(GeneratorContext ctx) {
        this.ctx = ctx;
    }

    private record Step(String place, String userType) {}

    // ------------------------------------------------------------------
    // Concatenación: '+' cuyo tipo resultante es CADENA.
    // Cada operando que no sea cadena se convierte primero a cadena y luego
    // se emite un 'concat'. Ej:  "Edad: " + edad  (edad es entero)
    //     t1 = int_to_str edad
    //     t2 = concat "Edad: ", t1
    // ------------------------------------------------------------------
    private String generarConcatenacion(Expression izquierda, Expression derecha) {
        String izq = ctx.generate(izquierda);
        izq = ctx.asString(ctx.typeOfOperand(izquierda, izq), izq);
        String der = ctx.generate(derecha);
        der = ctx.asString(ctx.typeOfOperand(derecha, der), der);
        String temp = ctx.newTemp(Type.CADENA);
        ctx.emit("concat", izq, der, temp, Type.CADENA);
        return temp;
    }

    public String visit(BinaryExprNode n) {
        if (n.operador.equals("&&") || n.operador.equals("||")) {
            return generarCortoCircuito(n);
        }
        if (n.operador.equals("+") && n.tipoResuelto == Type.CADENA) {
            return generarConcatenacion(n.izquierda, n.derecha);
        }
        String izq = ctx.generate(n.izquierda);
        String der = ctx.generate(n.derecha);
        Type tipo = n.tipoResuelto;
        String temp = ctx.newTemp(tipo);
        ctx.emit(n.operador, izq, der, temp, tipo);
        return temp;
    }

    private String generarCortoCircuito(BinaryExprNode n) {
        String resultado = ctx.newTemp(Type.BOOL);
        String etiquetaCorto = ctx.newLabel();
        String etiquetaFin = ctx.newLabel();
        boolean esAnd = n.operador.equals("&&");

        String izq = ctx.generate(n.izquierda);
        ctx.emit(esAnd ? "if_false" : "if_true", izq, null, etiquetaCorto, Type.VOID);
        String der = ctx.generate(n.derecha);
        ctx.emit(esAnd ? "if_false" : "if_true", der, null, etiquetaCorto, Type.VOID);

        ctx.emit("=", esAnd ? "1" : "0", null, resultado, Type.BOOL);
        ctx.emit("goto", null, null, etiquetaFin, Type.VOID);
        ctx.emitLabel(etiquetaCorto);
        ctx.emit("=", esAnd ? "0" : "1", null, resultado, Type.BOOL);
        ctx.emitLabel(etiquetaFin);

        return resultado;
    }

    public String visit(UnaryExprNode n) {
        String operando = ctx.generate(n.operando);
        Type tipo = n.tipoResuelto;
        String temp = ctx.newTemp(tipo);
        ctx.emit(n.operador, operando, null, temp, tipo);
        return temp;
    }

    public String visit(TernaryExprNode n) {
        Type tipo = n.tipoResuelto;
        String resultado = ctx.newTemp(tipo);
        String etiquetaFalso = ctx.newLabel();
        String etiquetaFin = ctx.newLabel();

        String cond = ctx.generate(n.condicion);
        ctx.emit("if_false", cond, null, etiquetaFalso, Type.VOID);
        String valorV = ctx.generate(n.siVerdadero);
        ctx.emit("=", valorV, null, resultado, tipo);
        ctx.emit("goto", null, null, etiquetaFin, Type.VOID);
        ctx.emitLabel(etiquetaFalso);
        String valorF = ctx.generate(n.siFalso);
        ctx.emit("=", valorF, null, resultado, tipo);
        ctx.emitLabel(etiquetaFin);

        return resultado;
    }

    public String visit(LiteralNode n) {
        String v = n.valorCrudo;
        if (v.equals("verdadero") || v.equals("true") || v.equals("verum")) return "1";
        if (v.equals("falso") || v.equals("false") || v.equals("falsus")) return "0";
        if (v.equals("null")) return "NULL"; // en C no existe 'null'
        return v;
    }

    public String visit(IdentifierNode n) {
        boolean esAtributo = ctx.currentClassFields().contains(n.nombre)
                && !ctx.currentMethodLocals().contains(n.nombre);
        return esAtributo ? "this->" + n.nombre : n.nombre;
    }

    /** Emite la llamada a un método de 'clase' sobre 'receptor' (o a una función suelta si clase == null). */
    private Step emitirLlamadaMetodo(String clase, String metodo, String receptor,
                                     CallSuffix cs, Type tipoFallback) {
        List<String> args = new ArrayList<>();
        List<Type> tipos = new ArrayList<>();
        if (clase != null) {
            args.add(receptor);          // 'this' del método
            tipos.add(Type.CLASE);
        }
        for (Expression arg : cs.argumentos) {
            args.add(ctx.generate(arg));
            tipos.add(ctx.typeOf((ASTNode) arg));
        }

        Type tipoRetorno = tipoFallback;
        String tipoUsrRetorno = null;
        FunctionDeclNode m = cs.metodoResuelto != null ? cs.metodoResuelto : ctx.findMethod(clase, metodo);
        if (m != null) {
            ResolvedType rt = ctx.resolveTypeText(m.tipoRetorno);
            tipoRetorno = rt.tipo();
            tipoUsrRetorno = rt.tipoUsuario();
        }

        String temp = ctx.newTempOf(tipoRetorno, tipoUsrRetorno);
        // La función se llama con el nombre de la clase que DECLARA el método (puede ser una superclase de 'clase').
        String duena = (m != null && m.claseDuena != null) ? m.claseDuena : clase;
        String nombreFuncion = duena != null ? duena + "_" + (m != null ? m.nombreCodigo() : metodo) : metodo;
        if (cs.esSuper) {
            ctx.emitirLlamadaDirecta(nombreFuncion, temp, tipoRetorno, args, tipos);   // super.metodo(): sin vtable
        } else {
            ctx.emitVariadic("call", nombreFuncion, temp, tipoRetorno, args, tipos);   // con vtable: polimorfismo
        }
        return new Step(temp, tipoUsrRetorno);
    }

    public String visit(PostfixExprNode n) {
        String nombreBase = (n.base instanceof IdentifierNode idb) ? idb.nombre : null;
        Type tipoFinal = n.tipoResuelto != Type.ERROR ? n.tipoResuelto : Type.ENTERO;

        // Llamada a un método de la propia clase sin 'this.':  estaVacia()  ==>  Pila_estaVacia(this)
        boolean llamadaImplicita = n.base instanceof IdentifierNode idm
                && !n.sufijos.isEmpty() && n.sufijos.get(0) instanceof CallSuffix
                && ctx.currentClass() != null && ctx.currentClassMethods().contains(idm.nombre)
                && !(ctx.context() != null && ctx.context().funciones.containsKey(idm.nombre));

        String direccion;
        String tipoUsr;
        int i = 0;
        if (llamadaImplicita) {
            Step p = emitirLlamadaMetodo(ctx.currentClass(), ((IdentifierNode) n.base).nombre, "this",
                    (CallSuffix) n.sufijos.get(0), tipoFinal);
            direccion = p.place();
            tipoUsr = p.userType();
            nombreBase = null;
            i = 1;
        } else {
            direccion = ctx.generate(n.base);
            tipoUsr = ctx.userTypeOfBase(n.base, direccion);
        }

        while (i < n.sufijos.size()) {
            int indicesSeguidos = ctx.countConsecutiveIndices(n.sufijos, i);

            if (indicesSeguidos > 0) {
                List<String> indices = new ArrayList<>();
                for (int k = 0; k < indicesSeguidos; k++) {
                    indices.add(ctx.generate(((IndexSuffix) n.sufijos.get(i + k)).indice));
                }
                String indiceFinal = ctx.flattenIndices(nombreBase, direccion, indices);
                Type tipoElemento = ctx.typeOfPlace(direccion);
                // Arreglo de objetos/estructuras (ej. Cuenta[] cuentas): el elemento es CLASE/ESTRUCTURA, no ERROR.
                if (tipoElemento == Type.ERROR && tipoUsr != null) {
                    ResolvedType re = ctx.resolveTypeText(tipoUsr);
                    if (re != null && re.tipo() != Type.ERROR) tipoElemento = re.tipo();
                }
                String temp = ctx.newTempOf(tipoElemento, tipoUsr);
                ctx.emit("getindex", direccion, indiceFinal, temp, tipoElemento);
                direccion = temp;
                nombreBase = null;
                i += indicesSeguidos;
                continue;
            }

            Suffix s = n.sufijos.get(i);

            // 1. MÉTODO: FieldSuffix + CallSuffix (ej. cima.getDato())
            if (s instanceof FieldSuffix fs && (i + 1 < n.sufijos.size()) && (n.sufijos.get(i + 1) instanceof CallSuffix cs)) {
                i += 2;
                String clase = tipoUsr != null ? tipoUsr : ctx.placeUserTypes().get(direccion);
                Step p = emitirLlamadaMetodo(clase, fs.nombreCampo, direccion, cs, tipoFinal);
                direccion = p.place();
                tipoUsr = p.userType();
                continue;
            }

            // 2. CAMPO NORMAL (ej. p1.x)
            if (s instanceof FieldSuffix fs) {
                ResolvedType campo = ctx.typeOfField(tipoUsr, fs.nombreCampo);
                Type tipoCampo = campo != null ? campo.tipo() : tipoFinal;
                if (campo != null && tipoCampo == Type.ERROR && campo.tipoUsuario() != null) {
                    ResolvedType rc = ctx.resolveTypeText(campo.tipoUsuario());   // atributo de tipo clase/estructura
                    if (rc != null && rc.tipo() != Type.ERROR) tipoCampo = rc.tipo();
                }
                tipoUsr = campo != null ? campo.tipoUsuario() : null;
                String temp = ctx.newTempOf(tipoCampo, tipoUsr);
                ctx.emit("getfield", direccion, fs.nombreCampo, temp, tipoCampo);
                direccion = temp;
                i++;
                continue;
            }

            // 3. LLAMADA A FUNCIÓN LIBRE (ej. funcionLibre(a, b))
            if (s instanceof CallSuffix cs) {
                Type tipoRetorno = tipoFinal;
                String tipoUsrRetorno = null;
                FunctionDeclNode f = ctx.context() != null ? ctx.context().funciones.get(direccion) : null;
                if (f != null) {
                    ResolvedType rt = ctx.resolveTypeText(f.tipoRetorno);
                    tipoRetorno = rt.tipo();
                    tipoUsrRetorno = rt.tipoUsuario();
                }
                List<String> args = new ArrayList<>();
                List<Type> tipos = new ArrayList<>();
                for (Expression arg : cs.argumentos) {
                    args.add(ctx.generate(arg));
                    tipos.add(ctx.typeOf((ASTNode) arg));
                }
                String temp = ctx.newTempOf(tipoRetorno, tipoUsrRetorno);
                ctx.emitVariadic("call", direccion, temp, tipoRetorno, args, tipos);
                direccion = temp;
                tipoUsr = tipoUsrRetorno;
                i++;
                continue;
            }

            i++;
        }
        return direccion;
    }

    public String visit(NewObjectNode n) {
        List<String> args = new ArrayList<>();
        List<Type> tipos = new ArrayList<>();

        Type tipo = Type.CLASE;
        String temp = ctx.newTemp(tipo);
        ctx.placeUserTypes().put(temp, n.nombreClase);

        args.add(temp);
        tipos.add(Type.CLASE);

        for (Expression arg : n.argumentos) {
            String lugar = ctx.generate(arg);
            args.add(lugar);
            tipos.add(ctx.typeOf((ASTNode) arg));
        }

        ctx.emitVariadic("new", n.nombreClase, temp, tipo, args, tipos);

        String initFunc = DeclarationGenerator.nombreInitPara(n.nombreClase, n.argumentos.size());
        String tempCall = ctx.newTemp(Type.VOID);
        ctx.emitVariadic("call", initFunc, tempCall, Type.VOID, args, tipos);

        return temp;
    }

    /** new int[n] / new boolean[f][c]: total = producto de los tamaños; las dimensiones viajan en la cuarteta. */
    public String visit(NewArrayNode n) {
        Type tipo = n.tipoResuelto;
        List<String> dims = new ArrayList<>();
        for (Expression d : n.dimensiones) dims.add(ctx.generate(d));
        String total = dims.get(0);
        for (int i = 1; i < dims.size(); i++) {
            String producto = ctx.newTemp(Type.ENTERO);
            ctx.emit("*", total, dims.get(i), producto, Type.ENTERO);
            total = producto;
        }
        String temp = ctx.newTempOf(tipo, n.tipoUsuarioResuelto);
        ctx.emitVariadic("newarray", total, temp, tipo, dims, java.util.Collections.nCopies(dims.size(), Type.ENTERO));
        return temp;
    }

    public String visit(ArrayLiteralNode n) {
        if (n.tipoResuelto == Type.ESTRUCTURA) {
            return generarLiteralEstructura(n);
        }
        Type tipo = n.tipoResuelto;
        List<Expression> valoresPlanos = new ArrayList<>();
        aplanarValores(n, valoresPlanos);

        String temp = ctx.newTemp(tipo);
        List<String> dimsLiteral = new ArrayList<>();
        for (int d : dimensionesDeLiteral(n)) dimsLiteral.add(String.valueOf(d));
        ctx.emitVariadic("newarray", String.valueOf(valoresPlanos.size()), temp, tipo, dimsLiteral,
                java.util.Collections.nCopies(dimsLiteral.size(), Type.ENTERO));
        for (int i = 0; i < valoresPlanos.size(); i++) {
            String valor = ctx.generate(valoresPlanos.get(i));
            ctx.emit("setindex", temp, String.valueOf(i), valor, tipo);
        }
        return temp;
    }

    /** {{1,2,3},{4,5,6}} -> [2, 3] */
    private List<Integer> dimensionesDeLiteral(ArrayLiteralNode n) {
        List<Integer> dims = new ArrayList<>();
        ArrayLiteralNode actual = n;
        while (actual != null) {
            dims.add(actual.valores.size());
            actual = (!actual.valores.isEmpty() && actual.valores.get(0) instanceof ArrayLiteralNode a
                    && a.tipoResuelto != Type.ESTRUCTURA) ? a : null;
        }
        return dims;
    }

    /**
     * Aplana un literal de arreglo posiblemente anidado a la secuencia de
     * valores escalares en orden "row-major" -- el mismo orden en el que
     * flattenIndices() calcula la posición de a[i][j][...]. Un literal de
     * estructuras ({} de un campo ESTRUCTURA) NO se aplana: ahí cada
     * elemento sigue siendo un valor propio, no una sub-dimensión.
     */
    private void aplanarValores(ArrayLiteralNode n, List<Expression> destino) {
        for (Expression valor : n.valores) {
            if (valor instanceof ArrayLiteralNode anidado && anidado.tipoResuelto != Type.ESTRUCTURA) {
                aplanarValores(anidado, destino);
            } else {
                destino.add(valor);
            }
        }
    }

    private String generarLiteralEstructura(ArrayLiteralNode n) {
        String tipoUsr = n.tipoUsuarioResuelto;
        String temp = ctx.newTempOf(Type.ESTRUCTURA, tipoUsr);
        ctx.emit("new", tipoUsr, null, temp, Type.ESTRUCTURA);

        var layout = ctx.context().layoutsEstructuras.get(tipoUsr);
        var nombresCampos = new ArrayList<>(layout.keySet());
        for (int i = 0; i < n.valores.size(); i++) {
            Expression valorExpr = n.valores.get(i);
            String valor = ctx.generate(valorExpr);
            Type tipoValor = ctx.typeOfOperand(valorExpr, valor);
            ctx.emit("setfield", temp, nombresCampos.get(i), valor, tipoValor);
        }
        return temp;
    }
}