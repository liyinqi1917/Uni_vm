import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// BackendWasm：Module -> WebAssembly Text（.wat）。
//
// 值模型：所有标量（INT、字符串指针、数组指针）在 WAT 中统一用 i64 表示。
// 字符串放在线性内存中（UTF-8、NUL 结尾）；数组使用线性内存中的 bump heap。
// 浏览器无法直接编译 .wat，因此 Main/Gui 同时生成配套 HTML 测试页，
// 页面通过 WABT 把 WAT 转成 wasm 后使用标准 WebAssembly API 执行。
public class BackendWasm {

    public static String emit(Module m) {
        return new Emitter(m).build();
    }

    // 生成一个可直接在浏览器打开的测试页。WAT 被内嵌进页面，避免 file:// 下的 fetch 限制。
    public static String browserTestPage(String wat, String title) {
        return HTML_TEMPLATE
                .replace("__TITLE__", htmlEscape(title))
                .replace("__WAT_JSON__", jsString(wat));
    }

    private static final String HTML_TEMPLATE = """
            <!doctype html>
            <html lang="zh-CN">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>UniVM WASM - __TITLE__</title>
              <style>
                body { margin: 0; padding: 24px; background: #0f172a; color: #e2e8f0;
                       font: 15px/1.55 ui-monospace, SFMono-Regular, Consolas, monospace; }
                h1 { margin: 0 0 8px; font-size: 20px; color: #7dd3fc; }
                #status { margin-bottom: 8px; color: #fbbf24; }
                #hint { margin-bottom: 16px; color: #94a3b8; font-size: 13px; }
                #output { min-height: 80px; padding: 16px; border: 1px solid #334155;
                          border-radius: 8px; background: #020617; white-space: pre-wrap; }
                details { margin-top: 18px; }
                summary { cursor: pointer; color: #94a3b8; }
                pre { overflow: auto; }
              </style>
            </head>
            <body>
              <h1>UniVM WebAssembly 测试</h1>
              <div id="status">正在加载 WABT 并编译 WAT...</div>
              <div id="hint">本页通过 WABT（unpkg CDN）在线编译 WAT；writeFile/readFile 使用浏览器内存虚拟文件系统。</div>
              <pre id="output"></pre>
              <details>
                <summary>查看生成的 WAT</summary>
                <pre id="wat"></pre>
              </details>

              <script src="https://unpkg.com/wabt@1.0.36/index.js"></script>
              <script>
                const WAT = __WAT_JSON__;
                const statusEl = document.getElementById("status");
                const outputEl = document.getElementById("output");
                document.getElementById("wat").textContent = WAT;
                const lines = [];
                let instance = null;
                const virtualFiles = new Map();

                function log(text) {
                  lines.push(String(text));
                  outputEl.textContent = lines.join("\\n") + "\\n";
                }

                function cString(memory, ptr) {
                  const bytes = new Uint8Array(memory.buffer);
                  const start = Number(ptr);
                  let end = start;
                  while (end < bytes.length && bytes[end] !== 0) end++;
                  return new TextDecoder().decode(bytes.subarray(start, end));
                }

                function putString(text) {
                  const bytes = new TextEncoder().encode(text);
                  const ptr = Number(instance.exports.alloc(BigInt(bytes.length + 1)));
                  const memory = new Uint8Array(instance.exports.memory.buffer);
                  memory.set(bytes, ptr);
                  memory[ptr + bytes.length] = 0;
                  return BigInt(ptr);
                }

                function emptyString() {
                  if (!instance || !instance.exports.alloc || !instance.exports.memory) return 0n;
                  return putString("");
                }

                async function run() {
                  if (typeof WabtModule !== "function") {
                    throw new Error("WABT 加载失败。请联网后重新打开本页，或使用 wat2wasm 手动转换。");
                  }
                  const wabt = await WabtModule();
                  const module = wabt.parseWat("univm.wat", WAT);
                  const binary = module.toBinary({ log: false, write_debug_names: true });
                  if (module.destroy) module.destroy();

                  const imports = {
                    env: {
                      print_int: (value) => log(value.toString()),
                      print_str: (ptr) => log(cString(instance.exports.memory, ptr)),
                      time: () => BigInt(Date.now()),
                      read_file: (pathPtr) => {
                        try {
                          const path = cString(instance.exports.memory, pathPtr);
                          if (virtualFiles.has(path)) {
                            return putString(virtualFiles.get(path));
                          }
                          const xhr = new XMLHttpRequest();
                          xhr.open("GET", path, false);
                          xhr.send(null);
                          if (xhr.status !== 0 && xhr.status !== 200) {
                            throw new Error("HTTP " + xhr.status);
                          }
                          return putString(xhr.responseText);
                        } catch (e) {
                          log("read_file 失败: " + e);
                          return emptyString();
                        }
                      },
                      write_file: (pathPtr, contentPtr) => {
                        try {
                          const path = cString(instance.exports.memory, pathPtr);
                          const content = cString(instance.exports.memory, contentPtr);
                          // 浏览器没有磁盘写入权限：用页面内存虚拟文件系统模拟，方便测试完整示例。
                          virtualFiles.set(path, content);
                          return 0n;
                        } catch (e) {
                          log("write_file 失败: " + e);
                          return -1n;
                        }
                      },
                      concat: (aPtr, bPtr) => {
                        try {
                          return putString(cString(instance.exports.memory, aPtr)
                                         + cString(instance.exports.memory, bPtr));
                        } catch (e) {
                          log("concat 失败: " + e);
                          return 0n;
                        }
                      },
                      str_int: (value) => {
                        try {
                          return putString(value.toString());
                        } catch (e) {
                          log("str_int 失败: " + e);
                          return 0n;
                        }
                      },
                      input: (promptPtr) => {
                        try {
                          const prompt = cString(instance.exports.memory, promptPtr);
                          const answer = window.prompt(prompt || "请输入：", "");
                          return putString(answer === null ? "" : answer);
                        } catch (e) {
                          log("input 失败: " + e);
                          return putString("");
                        }
                      },
                      to_int: (ptr) => {
                        try {
                          let s = cString(instance.exports.memory, ptr).trim();
                          if (s.startsWith("+")) s = s.slice(1);
                          if (!/^-?\\d+$/.test(s)) return 0n;
                          return BigInt(s);
                        } catch (e) {
                          return 0n;
                        }
                      },
                      random: (a, b) => {
                        try {
                          let lo = BigInt(a), hi = BigInt(b);
                          if (hi < lo) { const t = lo; lo = hi; hi = t; }
                          if (lo === hi) return lo;
                          const span = hi - lo + 1n;
                          const buf = new BigUint64Array(1);
                          if (globalThis.crypto && crypto.getRandomValues) crypto.getRandomValues(buf);
                          else buf[0] = BigInt(Math.floor(Math.random() * Number.MAX_SAFE_INTEGER));
                          if (span <= 0n) return BigInt.asIntN(64, buf[0]);
                          return lo + (buf[0] % span);
                        } catch (e) {
                          return 0n;
                        }
                      }
                    }
                  };

                  const result = await WebAssembly.instantiate(binary.buffer, imports);
                  instance = result.instance;
                  if (!instance.exports.main) throw new Error("WAT 未导出 main");
                  statusEl.textContent = "执行结果：";
                  instance.exports.main();
                  statusEl.textContent = "执行完成";
                }

                run().catch((e) => {
                  statusEl.textContent = "执行失败";
                  log(e && e.stack ? e.stack : e);
                });
              </script>
            </body>
            </html>
            """;

    private static String htmlEscape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    // 嵌入 <script> 的 JSON 字符串；同时避开 </script> 和 HTML 实体解析。
    private static String jsString(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                case '<':  b.append("\\u003c"); break;
                case '>':  b.append("\\u003e"); break;
                case '&':  b.append("\\u0026"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.append('"').toString();
    }

    private static final String ALLOC_FUNC = """
              (func $alloc (param $bytes i64) (result i64)
                (local $ptr i64)
                (local $pages i32)
                global.get $heap
                local.set $ptr
                global.get $heap
                local.get $bytes
                i64.add
                global.set $heap
                global.get $heap
                i64.const 65535
                i64.add
                i64.const 16
                i64.shr_u
                i32.wrap_i64
                local.set $pages
                local.get $pages
                memory.size
                i32.gt_u
                if
                  local.get $pages
                  memory.size
                  i32.sub
                  memory.grow
                  drop
                end
                local.get $ptr
              )
            """;

    private static final String ARRAY_NEW_FUNC = """
              (func $array_new (param $size i64) (result i64)
                (local $base i64)
                local.get $size
                i64.const 8
                i64.mul
                i64.const 8
                i64.add
                call $alloc
                local.set $base
                local.get $base
                i32.wrap_i64
                local.get $size
                i64.store
                local.get $base
                i64.const 8
                i64.add
              )
            """;

    private static final String STRLEN_FUNC = """
              (func $strlen (param $p i64) (result i64)
                (local $n i64)
                block $done
                  loop $loop
                    local.get $p
                    local.get $n
                    i64.add
                    i32.wrap_i64
                    i32.load8_u
                    i32.eqz
                    br_if $done
                    local.get $n
                    i64.const 1
                    i64.add
                    local.set $n
                    br $loop
                  end
                end
                local.get $n
              )
            """;

    private static class Emitter {

        private final Module module;
        private final int[] stringOffsets;
        private final List<byte[]> stringBytes = new ArrayList<>();
        private final int dataEnd;

        Emitter(Module m) {
            this.module = m;
            this.stringOffsets = new int[m.stringPool.size()];
            int off = 0;
            for (int i = 0; i < m.stringPool.size(); i++) {
                byte[] bytes = m.stringPool.get(i).getBytes(StandardCharsets.UTF_8);
                stringBytes.add(bytes);
                stringOffsets[i] = off;
                off += bytes.length + 1; // NUL 结尾
            }
            this.dataEnd = off;
        }

        String build() {
            boolean needPrintInt = anyNode(Opcode.PRINT_INT);
            boolean needPrintStr = anyNode(Opcode.PRINT_STR);
            boolean needRead = module.hasSysCall("readFile");
            boolean needWrite = module.hasSysCall("writeFile");
            boolean needTime = module.hasSysCall("time");
            boolean needInput = module.hasSysCall("input");
            boolean needToInt = module.hasSysCall("toInt");
            boolean needRandom = module.hasSysCall("random");
            boolean needConcat = anyNode(Opcode.SCONCAT);
            boolean needToStr = anyNode(Opcode.TOSTR);
            boolean needStrings = !module.stringPool.isEmpty() || needConcat
                    || needToStr || needInput;
            boolean needArrays = anyNode(Opcode.ANEW) || anyNode(Opcode.AGET)
                    || anyNode(Opcode.ASET) || anyNode(Opcode.ALEN);
            boolean needStrlen = anyNode(Opcode.SLEN);
            boolean needAlloc = needArrays || needRead || needInput || needConcat || needToStr;
            boolean needHeap = needAlloc;
            boolean needMemory = needStrings || needArrays || needRead || needWrite
                    || needPrintStr || needToInt;

            int heapStart = needHeap ? Math.max(8, align8(dataEnd)) : 0;
            int pages = needMemory ? Math.max(1, (int) (((long) Math.max(dataEnd, heapStart) + 65535) / 65536)) : 0;

            StringBuilder out = new StringBuilder();
            out.append(";; Generated by UniVM BackendWasm\n");
            out.append(";; Host imports: print_int(i64), print_str(i64), time()->i64,\n");
            out.append(";;               read_file(i64)->i64, write_file(i64,i64)->i64,\n");
            out.append(";;               concat(i64,i64)->i64, str_int(i64)->i64,\n");
            out.append(";;               input(i64)->i64, to_int(i64)->i64, random(i64,i64)->i64\n");
            out.append("(module\n");

            // ===== imports（必须位于函数定义之前）=====
            if (needPrintInt)
                out.append("  (import \"env\" \"print_int\" (func $print_int (param i64)))\n");
            if (needPrintStr)
                out.append("  (import \"env\" \"print_str\" (func $print_str (param i64)))\n");
            if (needTime)
                out.append("  (import \"env\" \"time\" (func $sys_time (result i64)))\n");
            if (needRead)
                out.append("  (import \"env\" \"read_file\" (func $sys_read_file (param i64) (result i64)))\n");
            if (needWrite)
                out.append("  (import \"env\" \"write_file\" (func $sys_write_file (param i64 i64) (result i64)))\n");
            if (needConcat)
                out.append("  (import \"env\" \"concat\" (func $sys_concat (param i64 i64) (result i64)))\n");
            if (needToStr)
                out.append("  (import \"env\" \"str_int\" (func $sys_str_int (param i64) (result i64)))\n");
            if (needInput)
                out.append("  (import \"env\" \"input\" (func $sys_input (param i64) (result i64)))\n");
            if (needToInt)
                out.append("  (import \"env\" \"to_int\" (func $sys_to_int (param i64) (result i64)))\n");
            if (needRandom)
                out.append("  (import \"env\" \"random\" (func $sys_random (param i64 i64) (result i64)))\n");

            // ===== 线性内存 / bump heap =====
            if (needMemory)
                out.append("  (memory (export \"memory\") ").append(pages).append(")\n");
            if (needHeap)
                out.append("  (global $heap (mut i64) (i64.const ").append(heapStart).append("))\n");

            if (needAlloc) out.append(ALLOC_FUNC);
            if (needArrays) out.append(ARRAY_NEW_FUNC);
            if (needStrlen) out.append(STRLEN_FUNC);

            // ===== 用户函数 =====
            for (Function fn : module.funcs.values()) {
                FuncEmitter e = new FuncEmitter(fn.params, stringOffsets, false);
                e.emitList(fn.body);
                out.append(e.finish(fn.name, true));
            }

            // ===== main =====
            FuncEmitter mainE = new FuncEmitter(new ArrayList<>(), stringOffsets, true);
            mainE.emitList(module.main);
            out.append(mainE.finish(null, false));

            // ===== 字符串常量数据段 =====
            for (int i = 0; i < stringBytes.size(); i++) {
                out.append("  (data (i32.const ").append(stringOffsets[i]).append(") \"")
                        .append(watBytes(stringBytes.get(i))).append("\\00\")\n");
            }

            // ===== exports =====
            out.append("  (export \"main\" (func $main))\n");
            if (needAlloc) out.append("  (export \"alloc\" (func $alloc))\n");
            out.append(")\n");
            return out.toString();
        }

        private boolean anyNode(Opcode want) {
            for (Function fn : module.funcs.values()) {
                if (hasNode(fn.body, want)) return true;
            }
            return hasNode(module.main, want);
        }

        private boolean hasNode(List<BytecodeNode> ns, Opcode want) {
            for (BytecodeNode n : ns) {
                if (n instanceof Instruction && ((Instruction) n).op == want) return true;
                if (n instanceof IfNode) {
                    IfNode f = (IfNode) n;
                    if (hasNode(f.cond, want) || hasNode(f.then, want) || hasNode(f.els, want)) return true;
                } else if (n instanceof WhileNode) {
                    WhileNode w = (WhileNode) n;
                    if (hasNode(w.cond, want) || hasNode(w.body, want)) return true;
                }
            }
            return false;
        }
    }

    private static class FuncEmitter {

        private final StringBuilder body = new StringBuilder();
        private final Map<String, Integer> slots = new HashMap<>();
        private final Deque<Integer> stack = new ArrayDeque<>();
        private final Deque<Integer> loops = new ArrayDeque<>(); // 当前循环的标签 id
        private final int[] stringOffsets;
        private final int paramCount;
        private final boolean isMain;
        private int nextLocal;
        private int labelCounter;

        FuncEmitter(List<String> params, int[] stringOffsets, boolean isMain) {
            this.stringOffsets = stringOffsets;
            this.isMain = isMain;
            this.paramCount = params.size();
            for (int i = 0; i < params.size(); i++) slots.put(params.get(i), i);
            this.nextLocal = params.size();
        }

        String finish(String fnName, boolean withResult) {
            StringBuilder f = new StringBuilder();
            f.append("  (func ");
            if (withResult) f.append("$f_").append(ident(fnName));
            else f.append("$main");
            for (int i = 0; i < paramCount; i++) f.append(" (param i64)");
            if (withResult) f.append(" (result i64)");
            int locals = nextLocal - paramCount;
            for (int i = 0; i < locals; i++) f.append("\n    (local i64)");
            f.append('\n').append(body);
            if (withResult) f.append("    i64.const 0\n");
            f.append("  )\n");
            return f.toString();
        }

        private int slot(String name) {
            Integer s = slots.get(name);
            if (s == null) {
                s = nextLocal++;
                slots.put(name, s);
            }
            return s;
        }

        private int temp() {
            return nextLocal++;
        }

        private int pop() {
            return stack.pop();
        }

        private void op(String s) {
            body.append("    ").append(s).append('\n');
        }

        private void emitList(List<BytecodeNode> ns) {
            for (BytecodeNode n : ns) emitNode(n);
        }

        private void emitNode(BytecodeNode n) {
            if (n instanceof Instruction) {
                emitInstruction((Instruction) n);
                return;
            }
            if (n instanceof IfNode) {
                IfNode f = (IfNode) n;
                emitList(f.cond);
                int c = pop();
                int base = stack.size();
                op("local.get " + c);
                op("i64.eqz");
                op("if");
                emitList(f.els);
                requireStack(base, "if else");
                op("else");
                emitList(f.then);
                requireStack(base, "if then");
                op("end");
                return;
            }
            if (n instanceof WhileNode) {
                WhileNode w = (WhileNode) n;
                int id = labelCounter++;
                int base = stack.size();
                op("block $exit" + id);
                op("loop $loop" + id);
                emitList(w.cond);
                int c = pop();
                op("local.get " + c);
                op("i64.eqz");
                op("br_if $exit" + id);
                loops.push(id);
                emitList(w.body);
                loops.pop();
                requireStack(base, "while body");
                op("br $loop" + id);
                op("end");
                op("end");
                return;
            }
            throw new RuntimeException("BackendWasm 不支持的字节码节点: " + n);
        }

        private void requireStack(int expected, String where) {
            if (stack.size() != expected) {
                throw new RuntimeException("BackendWasm 栈深度不一致（" + where + "）：期望 "
                        + expected + "，实际 " + stack.size());
            }
        }

        private void emitInstruction(Instruction i) {
            switch (i.op) {
                case PUSH: {
                    int t = temp();
                    op("i64.const " + i.operand);
                    op("local.set " + t);
                    stack.push(t);
                    break;
                }
                case SCONST: {
                    int t = temp();
                    op("i64.const " + stringOffsets[(int) i.operand]);
                    op("local.set " + t);
                    stack.push(t);
                    break;
                }
                case LOAD: {
                    int t = temp();
                    op("local.get " + slot(i.str));
                    op("local.set " + t);
                    stack.push(t);
                    break;
                }
                case STORE: {
                    int a = pop();
                    op("local.get " + a);
                    op("local.set " + slot(i.str));
                    break;
                }
                case IADD: case ISUB: case IMUL: case IDIV: {
                    int b = pop(), a = pop();
                    op("local.get " + a);
                    op("local.get " + b);
                    switch (i.op) {
                        case IADD: op("i64.add"); break;
                        case ISUB: op("i64.sub"); break;
                        case IMUL: op("i64.mul"); break;
                        default:   op("i64.div_s"); break;
                    }
                    op("local.set " + a);
                    stack.push(a);
                    break;
                }
                case INEG: {
                    int a = pop();
                    op("local.get " + a);
                    op("i64.const 0");
                    op("i64.sub");
                    op("local.set " + a);
                    stack.push(a);
                    break;
                }
                case IEQ: case INE: case ILT: case IGT: case ILE: case IGE: {
                    int b = pop(), a = pop();
                    op("local.get " + a);
                    op("local.get " + b);
                    switch (i.op) {
                        case IEQ: op("i64.eq"); break;
                        case INE: op("i64.ne"); break;
                        case ILT: op("i64.lt_s"); break;
                        case IGT: op("i64.gt_s"); break;
                        case ILE: op("i64.le_s"); break;
                        default:  op("i64.ge_s"); break;
                    }
                    op("i64.extend_i32_u");
                    op("local.set " + a);
                    stack.push(a);
                    break;
                }
                case PRINT_INT: {
                    int a = pop();
                    op("local.get " + a);
                    op("call $print_int");
                    break;
                }
                case PRINT_STR: {
                    int a = pop();
                    op("local.get " + a);
                    op("call $print_str");
                    break;
                }
                case SLEN: {
                    int a = pop();
                    op("local.get " + a);
                    op("call $strlen");
                    op("local.set " + a);
                    stack.push(a);
                    break;
                }
                case SCONCAT: {
                    int b = pop(), a = pop();
                    int t = temp();
                    op("local.get " + a);
                    op("local.get " + b);
                    op("call $sys_concat");
                    op("local.set " + t);
                    stack.push(t);
                    break;
                }
                case TOSTR: {
                    int a = pop();
                    int t = temp();
                    op("local.get " + a);
                    op("call $sys_str_int");
                    op("local.set " + t);
                    stack.push(t);
                    break;
                }
                case BREAK: {
                    if (loops.isEmpty())
                        throw new RuntimeException("BackendWasm: break 不在循环体内");
                    op("br $exit" + loops.peek());
                    break;
                }
                case CONTINUE: {
                    if (loops.isEmpty())
                        throw new RuntimeException("BackendWasm: continue 不在循环体内");
                    op("br $loop" + loops.peek());
                    break;
                }
                case POP:
                    pop();
                    break;
                case ANEW: {
                    int size = pop();
                    int t = temp();
                    op("local.get " + size);
                    op("call $array_new");
                    op("local.set " + t);
                    stack.push(t);
                    break;
                }
                case AGET: {
                    int idx = pop(), arr = pop();
                    int t = temp();
                    op("local.get " + arr);
                    op("local.get " + idx);
                    op("i64.const 8");
                    op("i64.mul");
                    op("i64.add");
                    op("i32.wrap_i64");
                    op("i64.load");
                    op("local.set " + t);
                    stack.push(t);
                    break;
                }
                case ASET: {
                    int val = pop(), idx = pop(), arr = pop();
                    op("local.get " + arr);
                    op("local.get " + idx);
                    op("i64.const 8");
                    op("i64.mul");
                    op("i64.add");
                    op("i32.wrap_i64");
                    op("local.get " + val);
                    op("i64.store");
                    break;
                }
                case ALEN: {
                    int arr = pop();
                    int t = temp();
                    op("local.get " + arr);
                    op("i64.const 8");
                    op("i64.sub");
                    op("i32.wrap_i64");
                    op("i64.load");
                    op("local.set " + t);
                    stack.push(t);
                    break;
                }
                case CALL: {
                    int argc = (int) i.operand;
                    int[] args = new int[argc];
                    for (int k = argc - 1; k >= 0; k--) args[k] = pop();
                    for (int k = 0; k < argc; k++) op("local.get " + args[k]);
                    op("call $f_" + ident(i.str));
                    int t = temp();
                    op("local.set " + t);
                    stack.push(t);
                    break;
                }
                case SYS_CALL: {
                    int argc = (int) i.operand;
                    if (i.str.equals("time")) {
                        if (argc != 0) throw new RuntimeException("time 参数个数错误: " + argc);
                        int t = temp();
                        op("call $sys_time");
                        op("local.set " + t);
                        stack.push(t);
                    } else if (i.str.equals("readFile")) {
                        if (argc != 1) throw new RuntimeException("readFile 参数个数错误: " + argc);
                        int path = pop();
                        int t = temp();
                        op("local.get " + path);
                        op("call $sys_read_file");
                        op("local.set " + t);
                        stack.push(t);
                    } else if (i.str.equals("writeFile")) {
                        if (argc != 2) throw new RuntimeException("writeFile 参数个数错误: " + argc);
                        int content = pop(), path = pop();
                        int t = temp();
                        op("local.get " + path);
                        op("local.get " + content);
                        op("call $sys_write_file");
                        op("local.set " + t);
                        stack.push(t);
                    } else if (i.str.equals("input")) {
                        if (argc != 1) throw new RuntimeException("input 参数个数错误: " + argc);
                        int prompt = pop();
                        int t = temp();
                        op("local.get " + prompt);
                        op("call $sys_input");
                        op("local.set " + t);
                        stack.push(t);
                    } else if (i.str.equals("toInt")) {
                        if (argc != 1) throw new RuntimeException("toInt 参数个数错误: " + argc);
                        int s = pop();
                        int t = temp();
                        op("local.get " + s);
                        op("call $sys_to_int");
                        op("local.set " + t);
                        stack.push(t);
                    } else if (i.str.equals("random")) {
                        if (argc != 2) throw new RuntimeException("random 参数个数错误: " + argc);
                        int hi = pop(), lo = pop();
                        int t = temp();
                        op("local.get " + lo);
                        op("local.get " + hi);
                        op("call $sys_random");
                        op("local.set " + t);
                        stack.push(t);
                    } else {
                        throw new RuntimeException("BackendWasm 未知标准库函数: " + i.str);
                    }
                    break;
                }
                case RET: {
                    int a = pop();
                    if (isMain) {
                        op("return");
                    } else {
                        op("local.get " + a);
                        op("return");
                    }
                    break;
                }
                default:
                    throw new RuntimeException("BackendWasm 不支持的指令: " + i.op);
            }
        }
    }

    // 把字符串 UTF-8 字节编码成 WAT 字符串内容（不含外层引号和结尾 NUL）。
    private static String watBytes(byte[] bytes) {
        StringBuilder b = new StringBuilder();
        for (byte x : bytes) {
            int v = x & 0xff;
            if (v == '"') b.append("\\\"");
            else if (v == '\\') b.append("\\\\");
            else if (v >= 0x20 && v <= 0x7e) b.append((char) v);
            else b.append(String.format("\\%02x", v));
        }
        return b.toString();
    }

    private static int align8(int n) {
        return (n + 7) & ~7;
    }

    private static String ident(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_') {
                b.append(c);
            } else {
                b.append('_');
            }
        }
        return b.toString();
    }
}
