import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// ArrayTypes：对函数体做流不敏感的抽象解释，推断哪些局部变量持有数组
// （0=INT, 1=ARRAY），供各后端用不同类型声明这些变量。
// 语句在操作数栈上是平衡的，因此可对结构化字节码跑一遍抽象栈；
// 因存在 `let b = a` 这类句柄拷贝，迭代到不动点。
public class ArrayTypes {

    public static Set<String> inferArrays(List<BytecodeNode> body, List<String> params,
                                          List<TypeChecker.T> paramTypes) {
        Map<String, Integer> t = new HashMap<>();
        // 数组参数按签名预置为 1：函数体从不 STORE 参数来源，不播种则推断不出
        for (int k = 0; k < params.size(); k++)
            t.put(params.get(k), paramTypes.get(k) == TypeChecker.T.ARRAY ? 1 : 0);

        boolean changed = true;
        while (changed) {
            changed = sim(body, t, new ArrayDeque<>());
        }

        Set<String> r = new HashSet<>();
        for (Map.Entry<String, Integer> e : t.entrySet()) if (e.getValue() == 1) r.add(e.getKey());
        return r;
    }

    private static int pop(Deque<Integer> st) { return st.isEmpty() ? 0 : st.pop(); }

    private static boolean sim(List<BytecodeNode> ns, Map<String, Integer> t, Deque<Integer> st) {
        boolean changed = false;
        for (BytecodeNode n : ns) {
            if (n instanceof Instruction) {
                Instruction i = (Instruction) n;
                switch (i.op) {
                    case PUSH: st.push(0); break;
                    case LOAD: st.push(t.getOrDefault(i.str, 0)); break;
                    case STORE: {
                        int v = pop(st);
                        Integer cur = t.get(i.str);
                        if (cur == null) { t.put(i.str, v); changed = true; }
                        else if (v == 1 && cur == 0) { t.put(i.str, 1); changed = true; }
                        break;
                    }
                    case IADD: case ISUB: case IMUL: case IDIV:
                        pop(st); pop(st); st.push(0); break;
                    case INEG: pop(st); st.push(0); break;
                    case IEQ: case INE: case ILT: case IGT: case ILE: case IGE:
                        pop(st); pop(st); st.push(0); break;
                    case PRINT_INT: case POP: case RET: pop(st); break;
                    case ANEW: st.push(1); break;
                    case AGET: pop(st); pop(st); st.push(0); break;
                    case ASET: pop(st); pop(st); pop(st); break;
                    case ALEN: pop(st); st.push(0); break;
                    case CALL: {
                        for (int k = 0; k < i.operand; k++) pop(st);
                        st.push(0);
                        break;
                    }
                }
            } else if (n instanceof IfNode) {
                IfNode f = (IfNode) n;
                changed |= sim(f.cond, t, st);
                changed |= sim(f.then, t, st);
                changed |= sim(f.els, t, st);
            } else if (n instanceof WhileNode) {
                WhileNode w = (WhileNode) n;
                changed |= sim(w.cond, t, st);
                changed |= sim(w.body, t, st);
            }
        }
        return changed;
    }
}
