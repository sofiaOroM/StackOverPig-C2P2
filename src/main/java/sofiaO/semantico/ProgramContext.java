package sofiaO.semantico;

import sofiaO.ast.declaracion.ClassDeclNode;
import sofiaO.ast.declaracion.ConstructorDeclNode;
import sofiaO.ast.declaracion.FunctionDeclNode;
import sofiaO.ast.declaracion.StructDeclNode;
import sofiaO.util.Type;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HashMap;
import java.util.List;

/**
 * Todo lo que un programa conoce "globalmente" a través de sus imports:
 * qué estructuras (.y), qué clases (.z) y qué funciones libres (.y) existen,
 * más el "layout" ya calculado de cada una (nombre de campo -> tipo resuelto).
 */
public class ProgramContext {

    public final Map<String, StructDeclNode> structs = new HashMap<>();
    public final Map<String, ClassDeclNode> classes = new HashMap<>();
    public final Map<String, FunctionDeclNode> funciones = new HashMap<>();

    /** nombreEstructura -> (nombreCampo -> tipo resuelto del campo) */
    public final Map<String, Map<String, ResolvedType>> layoutsEstructuras = new HashMap<>();

    /** nombreEstructura -> (nombreCampo -> dimensiones, solo para campos que son arreglo fijo, ej. notas[3]) */
    public final Map<String, Map<String, List<Integer>>> dimensionesCamposEstructuras = new HashMap<>();

    /** nombreClase -> (nombreAtributo -> tipo resuelto del atributo) */
    public final Map<String, Map<String, ResolvedType>> layoutsClases = new HashMap<>();

    /** nombreClase -> (nombreAtributo -> dimensiones, solo para atributos que son arreglo fijo) */
    public final Map<String, Map<String, List<Integer>>> dimensionesCamposClases = new HashMap<>();

    /** nombreClase -> (nombreMetodo -> declaración del método) */
    public final Map<String, Map<String, FunctionDeclNode>> metodosPorClase = new HashMap<>();

    /** nombreClase -> (nombreMetodo -> TODAS las sobrecargas de ese nombre) */
    public final Map<String, Map<String, List<FunctionDeclNode>>> sobrecargasPorClase = new HashMap<>();

    /** nombreClase -> paquete donde está declarada (carpeta relativa, "" = paquete por defecto). Encapsulamiento. */
    public final Map<String, String> paquetesPorClase = new HashMap<>();

    /** clase hija -> clase padre (herencia). Lo llena SemanticAnalyzer.registrarSuperclase al leer un 'extends'. */
    public final Map<String, String> superclases = new HashMap<>();

    /** nombreClase -> lista de constructores (puede haber varios por sobrecarga) */
    public final Map<String, List<ConstructorDeclNode>> constructoresPorClase = new HashMap<>();

    /** Cadena de herencia de la clase, de la raíz (el ancestro más lejano) hasta la propia clase. */
    public List<String> cadenaDeHerencia(String clase) {
        java.util.ArrayDeque<String> cadena = new java.util.ArrayDeque<>();
        int guarda = 0;
        for (String c = clase; c != null && guarda++ < 100; c = superclases.get(c)) cadena.addFirst(c);
        return new java.util.ArrayList<>(cadena);
    }

    /** Atributos de la clase INCLUIDOS los heredados (los del padre primero). layoutsClases solo guarda los propios. */
    public Map<String, ResolvedType> layoutCompletoDeClase(String clase) {
        if (!layoutsClases.containsKey(clase)) return null;
        Map<String, ResolvedType> completo = new LinkedHashMap<>();
        for (String c : cadenaDeHerencia(clase)) {
            Map<String, ResolvedType> propio = layoutsClases.get(c);
            if (propio != null) completo.putAll(propio);
        }
        return completo;
    }

    public Map<String, List<Integer>> dimensionesCompletasDeClase(String clase) {
        Map<String, List<Integer>> completo = new LinkedHashMap<>();
        for (String c : cadenaDeHerencia(clase)) {
            Map<String, List<Integer>> propio = dimensionesCamposClases.get(c);
            if (propio != null) completo.putAll(propio);
        }
        return completo;
    }

    public Map<String, Map<String, ResolvedType>> obtenerLayoutsParaCEmitter() {
        Map<String, Map<String, ResolvedType>> resultado = new LinkedHashMap<>();

        if (this.layoutsEstructuras != null) {
            for (var entry : this.layoutsEstructuras.entrySet()) {
                resultado.put(entry.getKey(), new LinkedHashMap<>(entry.getValue()));
            }
        }

        if (this.layoutsClases != null) {
            // En C cada clase es un struct "plano": lleva también los atributos heredados (los del padre primero).
            for (String clase : this.layoutsClases.keySet()) {
                resultado.put(clase, new LinkedHashMap<>(layoutCompletoDeClase(clase)));
            }
        }

        return resultado;
    }

    public Map<String, Map<String, List<Integer>>> obtenerDimensionesCamposParaCEmitter() {
        Map<String, Map<String, List<Integer>>> resultado = new LinkedHashMap<>();

        if (this.dimensionesCamposEstructuras != null) {
            for (var entry : this.dimensionesCamposEstructuras.entrySet()) {
                resultado.put(entry.getKey(), new LinkedHashMap<>(entry.getValue()));
            }
        }

        if (this.dimensionesCamposClases != null) {
            for (String clase : this.layoutsClases.keySet()) {
                Map<String, List<Integer>> dims = dimensionesCompletasDeClase(clase);
                if (!dims.isEmpty()) resultado.put(clase, dims);
            }
        }

        return resultado;
    }
}
