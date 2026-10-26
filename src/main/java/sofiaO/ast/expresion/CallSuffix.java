package sofiaO.ast.expresion;

import sofiaO.ast.Expression;
import sofiaO.ast.declaracion.FunctionDeclNode;

import java.util.List;

/** (args)  llamada a función libre o a método sobre el resultado del sufijo anterior */
public class CallSuffix implements Suffix {
    public List<Expression> argumentos;
    /** Método elegido por el semántico (resuelve la sobrecarga); null si es una función libre. */
    public FunctionDeclNode metodoResuelto;
    /** true en super.metodo(...): se busca desde la superclase y se llama a ESA versión (sin despacho dinámico). */
    public boolean esSuper = false;
    public CallSuffix(List<Expression> argumentos) { this.argumentos = argumentos; }
}
