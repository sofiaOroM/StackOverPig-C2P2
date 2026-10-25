package sofiaO.ast.expresion;

import sofiaO.ast.Expression;
import sofiaO.ast.declaracion.FunctionDeclNode;

import java.util.List;

/** (args)  llamada a función libre o a método sobre el resultado del sufijo anterior */
public class CallSuffix implements Suffix {
    public List<Expression> argumentos;
    /** Método elegido por el semántico (resuelve la sobrecarga); null si es una función libre. */
    public FunctionDeclNode metodoResuelto;
    public CallSuffix(List<Expression> argumentos) { this.argumentos = argumentos; }
}
