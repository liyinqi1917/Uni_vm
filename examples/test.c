# UniVM C 风格前端测试：复用 .uni 的 Lexer / Parser
func sum(arr: array) {
    let i = 0;
    let s = 0;
    while (i < len(arr)) {
        s = s + arr[i];
        i = i + 1;
    }
    return s;
}

func fib(n) {
    if (n < 2) {
        return n;
    }
    return fib(n - 1) + fib(n - 2);
}

let a = [5];
let i = 0;
while (i < len(a)) {
    a[i] = fib(i);
    i = i + 1;
}

print sum(a);
print fib(10);
print 7 / 2;
print -7 / 2;
