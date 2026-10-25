package sofiaO.Traductor.generators;

import sofiaO.ast.ASTNode;
import sofiaO.ast.Statement;
import sofiaO.ast.declaracion.*;
import sofiaO.util.Type;
import sofiaO.util.TypeMapper;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Genera cuartetas para todo lo que declara algo: programa, clases, funciones, variables. */
public class DeclarationGenerator {

    private final GeneratorContext ctx;

    public DeclarationGenerator(GeneratorContext ctx) {
        this.ctx = ctx;
    }

    public String visit(ProgramNode n) {
        for (var d : n.declaraciones) ctx.generate(d);
        ctx.emit("halt", null, null, null, Type.VOID);
        return null;
    }

    public String visit(ImportNode n) { return null; }

    public String visit(StructDeclNode n) {
        TypeMapper.registrarTipoUsuario(n.nombre, Type.ESTRUCTURA);
        return null;
    }

    public String visit(FieldNode n) { return null; }

    public String visit(ParamNode n) {
        Type tipo = TypeMapper.deTexto(n.tipo);
        if (tipo == Type.ERROR) {
            tipo = TypeMapper.deUsuario(n.tipo);
            if (tipo == Type.ESTRUCTURA || tipo == Type.CLASE) {
                ctx.placeUserTypes().put(n.nombre, n.tipo);
            }
        }
        ctx.placeTypes().put(n.nombre, tipo);
        if (!n.dimensionesArreglo.isEmpty()) {
            ctx.arrayDimensions().put(n.nombre, n.dimensionesArreglo);
        }
        String tipoDeclarado = n.tipo + (n.modo == ParamNode.ModoPaso.REFERENCIA_ARREGLO ? "[]" : "");
        ctx.emit("param", n.nombre, tipoDeclarado, null, tipo);
        return null;
    }

    public String visit(ClassDeclNode n) {
        TypeMapper.registrarTipoUsuario(n.nombre, Type.CLASE);
        Set<String> nombresAtributos = new HashSet<>();
        for (var atributo : n.atributos) nombresAtributos.add(atributo.nombre);
        Set<String> nombresMetodos = new HashSet<>();
        for (var m : n.metodos) nombresMetodos.add(m.nombre);
        agregarHeredados(n.nombre, nombresAtributos, nombresMetodos);

        ctx.enterClass(n.nombre, nombresAtributos, nombresMetodos);
        if (n.constructores.isEmpty()) {
            generarConstructorPorDefecto(n.nombre);
        }
        for (var c : n.constructores) ctx.generate(c);
        for (var m : n.metodos) ctx.generate(m);
        ctx.exitClass();
        return null;
    }

    /** Atributos y métodos de las superclases también se acceden sin 'this.' dentro de la clase hija. */
    private void agregarHeredados(String clase, Set<String> atributos, Set<String> metodos) {
        if (ctx.context() == null) return;
        int guarda = 0;
        for (String c = ctx.context().superclases.get(clase); c != null && guarda++ < 100; c = ctx.context().superclases.get(c)) {
            var layout = ctx.context().layoutsClases.get(c);
            if (layout != null) atributos.addAll(layout.keySet());
            var porNombre = ctx.context().sobrecargasPorClase.get(c);
            if (porNombre != null) metodos.addAll(porNombre.keySet());
        }
    }

    private void generarConstructorPorDefecto(String claseDuena) {
        String nombreCompleto = nombreInitPara(claseDuena, 0);
        ctx.emit("func", "void", null, nombreCompleto, Type.VOID);
        ctx.startLocals(new HashSet<>());
        ctx.emit("param", "this", null, null, Type.CLASE);
        ctx.placeTypes().put("this", Type.CLASE);
        ctx.placeUserTypes().put("this", claseDuena);
        ctx.emit("endfunc", null, null, nombreCompleto, Type.VOID);
    }

    public String visit(FunctionDeclNode n) {
        String nombreCompleto = n.esMetodo ? n.claseDuena + "_" + n.nombreCodigo() : n.nombre;
        // arg1 = tipo de retorno declarado (null = void); lo usa CEmitter para la firma en C
        ctx.emit("func", n.tipoRetorno, null, nombreCompleto, Type.VOID);

        Set<String> nombresParametros = new HashSet<>();
        for (ParamNode p : n.parametros) nombresParametros.add(p.nombre);
        ctx.startLocals(nombresParametros);

        if (n.esMetodo) {
            ctx.emit("param", "this", null, null, Type.CLASE);
            ctx.placeTypes().put("this", Type.CLASE);
            ctx.placeUserTypes().put("this", n.claseDuena);
        }

        for (ParamNode p : n.parametros) ctx.generate(p);
        for (Statement s : n.cuerpo) ctx.generate(s);
        ctx.emit("endfunc", null, null, nombreCompleto, Type.VOID);
        return null;
    }

    public String visit(ConstructorDeclNode n) {
        // Zetariano sí permite sobrecargar constructores (Registro(), Registro(inicial)...),
        // pero C no permite dos funciones con el mismo nombre. Sin este sufijo, dos
        // constructores de la misma clase con distinta cantidad de parámetros generaban
        // ambos "Clase_init" y el C resultante no compilaba ("conflicting types").
        String nombreCompleto = nombreInitPara(n.claseDuena, n.parametros.size());
        ctx.emit("func", "void", null, nombreCompleto, Type.VOID);
        ctx.emit("param", "this", null, null, Type.CLASE);
        ctx.placeTypes().put("this", Type.CLASE);
        ctx.placeUserTypes().put("this", n.claseDuena);

        Set<String> nombresParametros = new HashSet<>();
        for (ParamNode p : n.parametros) nombresParametros.add(p.nombre);
        ctx.startLocals(nombresParametros);

        for (ParamNode p : n.parametros) ctx.generate(p);
        for (Statement s : n.cuerpo) ctx.generate(s);
        ctx.emit("endfunc", null, null, nombreCompleto, Type.VOID);
        return null;
    }

    public String visit(VarDeclNode n) {
        Type tipo = TypeMapper.deTexto(n.tipo);
        if (tipo == Type.ERROR) {
            tipo = TypeMapper.deUsuario(n.tipo);
            if (tipo == Type.ESTRUCTURA || tipo == Type.CLASE) {
                ctx.placeUserTypes().put(n.nombre, n.tipo);
            }
        }
        ctx.placeTypes().put(n.nombre, tipo);

        if (!n.dimensionesArreglo.isEmpty()) {
            ctx.arrayDimensions().put(n.nombre, n.dimensionesArreglo);
            int total = 1;
            for (int d : n.dimensionesArreglo) total *= Math.max(d, 0);
            ctx.emit("declare", String.valueOf(total), tipoUsuarioDeclarado(n, tipo), n.nombre, tipo);
            boolean tamanosConstantes = n.dimensionesArreglo.stream().allMatch(d -> d >= 0);
            if (n.inicializador == null && tamanosConstantes) {
                String tipoUsr = ctx.placeUserTypes().get(n.nombre);
                String temp = ctx.newTempOf(tipo, tipoUsr);
                ctx.emitVariadic("newarray", String.valueOf(total), temp, tipo, dimensionesComoTexto(n.dimensionesArreglo),
                        java.util.Collections.nCopies(n.dimensionesArreglo.size(), Type.ENTERO));
                ctx.emit("=", temp, null, n.nombre, tipo);

                if (tipo == Type.ESTRUCTURA) {
                    for (int idx = 0; idx < total; idx++) {
                        String elemento = ctx.newTempOf(Type.ESTRUCTURA, tipoUsr);
                        ctx.emit("getindex", n.nombre, String.valueOf(idx), elemento, Type.ESTRUCTURA);
                        inicializarCamposEstructura(elemento, tipoUsr);
                    }
                }
            }
        } else {
            ctx.emit("declare", null, tipoUsuarioDeclarado(n, tipo), n.nombre, tipo);
        }

        if (n.inicializador != null) {
            String valor = ctx.generate((ASTNode) n.inicializador);
            ctx.emit("=", valor, null, n.nombre, tipo);
        } else if (tipo == Type.ESTRUCTURA && n.dimensionesArreglo.isEmpty()) {
            String tipoUsr = ctx.placeUserTypes().get(n.nombre);
            ctx.emit("new", tipoUsr, null, n.nombre, Type.ESTRUCTURA);
            inicializarCamposEstructura(n.nombre, tipoUsr);
        }
        return null;
    }

    /** arg2 de 'declare': nombre del tipo de usuario (estructura/clase) o null. Lo usa el backend RISC-V. */
    private static String tipoUsuarioDeclarado(VarDeclNode n, Type tipo) {
        return (tipo == Type.ESTRUCTURA || tipo == Type.CLASE) ? n.tipo : null;
    }

    private static List<String> dimensionesComoTexto(List<Integer> dims) {
        List<String> texto = new java.util.ArrayList<>();
        for (int d : dims) texto.add(String.valueOf(d));
        return texto;
    }

    static String nombreInitPara(String clase, int cantidadParametros) {
        return clase + "_init" + (cantidadParametros == 0 ? "" : "_" + cantidadParametros);
    }

    private void inicializarCamposEstructura(String lugar, String tipoUsr) {
        if (tipoUsr == null || ctx.context() == null) return;
        var layout = ctx.context().layoutsEstructuras.get(tipoUsr);
        if (layout == null) return;
        var dimensionesDelTipo = ctx.context().dimensionesCamposEstructuras.get(tipoUsr);

        for (var campo : layout.entrySet()) {
            if (campo.getValue().tipo() != Type.ESTRUCTURA) continue;
            String nombreCampo = campo.getKey();
            String tipoCampo = campo.getValue().tipoUsuario();
            List<Integer> dimensionesCampo = dimensionesDelTipo == null ? null : dimensionesDelTipo.get(nombreCampo);

            if (dimensionesCampo != null && !dimensionesCampo.isEmpty() && dimensionesCampo.get(0) >= 0) {
                int total = 1;
                for (int d : dimensionesCampo) total *= d;
                String arreglo = ctx.newTempOf(campo.getValue().tipo(), tipoCampo);
                ctx.emitVariadic("newarray", String.valueOf(total), arreglo, Type.ESTRUCTURA, dimensionesComoTexto(dimensionesCampo),
                        java.util.Collections.nCopies(dimensionesCampo.size(), Type.ENTERO));
                ctx.emit("setfield", lugar, nombreCampo, arreglo, Type.ESTRUCTURA);
                for (int idx = 0; idx < total; idx++) {
                    String elemento = ctx.newTempOf(Type.ESTRUCTURA, tipoCampo);
                    ctx.emit("getindex", arreglo, String.valueOf(idx), elemento, Type.ESTRUCTURA);
                    inicializarCamposEstructura(elemento, tipoCampo);
                }
            } else if (dimensionesCampo == null) {
                String temp = ctx.newTempOf(Type.ESTRUCTURA, tipoCampo);
                ctx.emit("new", tipoCampo, null, temp, Type.ESTRUCTURA);
                ctx.emit("setfield", lugar, nombreCampo, temp, Type.ESTRUCTURA);
                inicializarCamposEstructura(temp, tipoCampo);
            }
        }
    }
}