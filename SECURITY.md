# Security Policy

## 报告漏洞

如果你发现安全问题，请不要开公开 Issue。

发邮件到 liyinqi1971@gmail.com，或者在 GitHub 上用
[Private vulnerability reporting](https://github.com/liyinqi1917/Uni_vm/security/advisories/new)
提交。

我会在 7 天内回复。确认后会在修复版本中致谢（除非你要求匿名）。

## 范围

UniVM 是编译器实验项目，不涉及网络服务、用户数据、加密。
主要安全关注点：

- 生成的 C / C++ 代码是否有缓冲区溢出风险
- GC 是否有 use-after-free 可能
- Polyglot 桥接是否允许 guest language 越权访问宿主

## 支持版本

只有最新 `main` 分支接受安全修复。