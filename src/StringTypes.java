import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// StringTypes：对函数体做流不敏感的抽象解释，推断哪些局部变量持有字符串。
// 与 ArrayTypes 思路一致：模拟操作数栈，迭代到不动点（处理 let b = a 的句柄拷贝），
// 供 C/C++/Java/Go 后端用正确的静态类型声明这些变量。
public class StringTypes {

    public static Set<String> inferStrings(List<BytecodeNode> body, List<String> params,
                                           List<TypeChecker.T> paramTypes) {
        Map<String, Boolean> t = new HashMap<>();
        for (int k = 0; k < params.size(); k++)
            t.put(params.get(k), paramTypes.get(k) == TypeChecker.T.STRING);

        boolean changed = true;
        while (changed) {
            changed = sim(body, t, new ArrayDeque<>());
        }

        Set<String> r = new HashSet<>();
        for (Map.Entry<String, Boolean> e : t.entrySet()) if (e.getValue()) r.add(e.getKey());
        return r;
    }

    private static boolean pop(Deque<Boolean> st) {
        return !st.isEmpty() && st.pop();
    }

    private static boolean sim(List<BytecodeNode> ns, Map<String, Boolean> t, Deque<Boolean> st) {
        boolean changed = false;
        for (BytecodeNode n : ns) {
            if (n instanceof Instruction) {
                Instruction i = (Instruction) n;
                switch (i.op) {
                    case PUSH: st.push(false); break;
                    case SCONST: st.push(true); break;
                    case LOAD: st.push(t.getOrDefault(i.str, false)); break;
                    case STORE: {
                        boolean v = pop(st);
                        Boolean cur = t.get(i.str);
                        if (cur == null) { t.put(i.str, v); changed = true; }
                        else if (v && !cur) { t.put(i.str, true); changed = true; }
                        break;
                    }
                    case IADD: case ISUB: case IMUL: case IDIV:
                        pop(st); pop(st); st.push(false); break;
                    case INEG:
                        pop(st); st.push(false); break;
                    case IEQ: case INE: case ILT: case IGT: case ILE: case IGE:
                        pop(st); pop(st); st.push(false); break;
                    case PRINT_INT: case PRINT_STR: case POP: case RET:
                        pop(st); break;
                    case SLEN:
                        pop(st); st.push(false); break;
                    case ANEW:
                        pop(st); st.push(false); break;
                    case AGET:
                        pop(st); pop(st); st.push(false); break;
                    case ASET:
                        pop(st); pop(st); pop(st); break;
                    case ALEN:
                        pop(st); st.push(false); break;
                    case CALL: {
                        for (int k = 0; k < i.operand; k++) pop(st);
                        st.push(false);
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
