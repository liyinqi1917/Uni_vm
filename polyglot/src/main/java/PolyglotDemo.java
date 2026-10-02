import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;

public class PolyglotDemo {

    public static void main(String[] args) {
        System.out.println("==== UniVM Polyglot Demo ====");

        try {
            Class.forName("org.graalvm.polyglot.Context");
        } catch (ClassNotFoundException e) {
            System.err.println("当前 JDK 不是 GraalVM，无法运行 Polyglot 演示。");
            return;
        }

        try (Context context = Context.newBuilder()
                .allowAllAccess(true)
                .build()) {

            context.getBindings("js").putMember("addOne",
                    (java.util.function.IntUnaryOperator) x -> x + 1);

            int r1 = context.eval("js", "addOne(41)").asInt();
            System.out.println("[JS -> Java] addOne(41) = " + r1);

            context.getBindings("js").putMember("uniHeap", new UniHeapStub());
            int r2 = context.eval("js", "uniHeap.load(7) + uniHeap.load(35)").asInt();
            System.out.println("[JS -> UniHeap] load(7) + load(35) = " + r2);

            context.getBindings("js").putMember("makeArray",
                    (java.util.function.IntFunction<int[]>) n -> {
                        int[] a = new int[n];
                        for (int i = 0; i < n; i++) a[i] = i * i;
                        return a;
                    });

            Value arr = context.eval("js", "makeArray(5)");
            System.out.print("[JS -> Java array] squares = ");
            for (long i = 0; i < arr.getArraySize(); i++) {
                System.out.print(arr.getArrayElement(i).asInt() + " ");
            }
            System.out.println();

            System.out.println("==== Demo 完成 ====");

        } catch (Exception e) {
            System.err.println("[Polyglot] 运行失败：" + e.getMessage());
        }
    }

    public static class UniHeapStub {
        public int load(int addr) {
            return addr + 1;
        }
    }
}