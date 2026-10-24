# Python 前端同签名验收
def sum(arr: array):
    i = 0
    s = 0
    while i < len(arr):
        s = s + arr[i]
        i = i + 1
    return s

a = [5]
j = 0
while j < len(a):
    a[j] = j + 1
    j = j + 1
print(sum(a))
