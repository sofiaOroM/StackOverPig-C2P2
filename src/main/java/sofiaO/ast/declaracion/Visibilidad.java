package sofiaO.ast.declaracion;

/**
 * Modificador de acceso de un atributo, método o constructor de Zetariano (encapsulamiento).
 *
 *  - PUBLIC    : accesible desde cualquier clase y paquete.
 *  - PRIVATE   : solo dentro de la misma clase donde se declaró.
 *  - PROTECTED : dentro del mismo paquete y desde subclases (aunque estén en otro paquete).
 *  - DEFAULT   : sin modificador (package-private): solo dentro del mismo paquete.
 *
 * Los miembros que no vienen de Zetariano (campos de estructuras Y?, funciones libres) son PUBLIC.
 */
public enum Visibilidad {
    PUBLIC("public"),
    PRIVATE("private"),
    PROTECTED("protected"),
    DEFAULT("default");

    private final String palabra;

    Visibilidad(String palabra) { this.palabra = palabra; }

    /** Texto para los mensajes de error: "public", "private", "protected" o "default (sin modificador)". */
    public String etiqueta() {
        return this == DEFAULT ? "default (sin modificador)" : palabra;
    }

    /** Convierte el texto del token ("public", "private", "protected") o null/vacío en DEFAULT. */
    public static Visibilidad deTexto(String texto) {
        if (texto == null || texto.isEmpty()) return DEFAULT;
        for (Visibilidad v : values()) {
            if (v.palabra.equals(texto)) return v;
        }
        return DEFAULT;
    }
}
