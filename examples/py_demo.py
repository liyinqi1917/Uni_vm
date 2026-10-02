# Python 风格前端演示（M5）：缩进块、def、elif、无分号
def add(a, b):
    return a + b

def fact(n):
    if n <= 1:
        return 1
    return n * fact(n - 1)

def fib(n):
    if n < 2:
        return n
    return fib(n - 1) + fib(n - 2)

def grade(x):
    if x >= 90:
        return 3
    elif x >= 60:
        return 2
    else:
        return 1

total = 0
i = 1
while i <= 100:
    total = total + i
    i = i + 1

print(add(3, 4))
print(fact(5))
print(fact(10))
print(fib(10))
print(grade(95))
print(grade(70))
print(grade(10))
print(total)
