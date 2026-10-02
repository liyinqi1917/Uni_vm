import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// Heap：UniVM 运行时堆 + 追踪式 GC（M6）。
// 数组对象由 handle（从 HANDLE_BASE 起的 long）标识，VM/JIT 的槽位与栈
// 都用 long 持有 handle。GC 采用"保守标记"（Boehm 思路）：枚举所有根
// （各帧局部变量 + 操作数栈），凡值等于现存 handle 即标记；数组元素只有
// int、不含引用，因此不需要追踪进对象内部。未标记对象被清扫。
// 保守性只会让"数据恰好等于某 handle"的垃圾多活一轮，绝不会释放存活对象。
public class Heap {

    public static class ArrayObj {
        public final long[] data;
        ArrayObj(int n) { this.data = new long[n]; }
    }

    private static final long HANDLE_BASE = 1_000_000L;
    private long nextHandle = HANDLE_BASE;

    public final Map<Long, ArrayObj> objects = new HashMap<>();

    public long totalAlloc;
    public long gcRuns;
    public long lastFreed;
    public long lastLive;

    public long alloc(int n) {
        long h = nextHandle++;
        objects.put(h, new ArrayObj(n));
        totalAlloc++;
        return h;
    }

    public ArrayObj get(long h) { return objects.get(h); }

    // 根以若干 long[] 给出；返回本次回收对象数
    public int collect(List<long[]> rootBlocks) {
        java.util.HashSet<Long> mark = new java.util.HashSet<>();
        for (long[] blk : rootBlocks) {
            if (blk == null) continue;
            for (long v : blk) {
                if (objects.containsKey(v)) mark.add(v);
            }
        }
        int before = objects.size();
        objects.keySet().retainAll(mark);
        int live = objects.size();
        int freed = before - live;
        gcRuns++;
        lastFreed = freed;
        lastLive = live;
        return freed;
    }
}
