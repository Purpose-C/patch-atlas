# 浏览器端到端测试

这套用例跑在真实 Chromium 里，对着 `./scripts/up.sh` 拉起的打包控制台。**测试自身不启停栈，也不往数据库写数据。**

前置：

```bash
./scripts/up.sh
```

然后：

```bash
cd frontend
npx playwright install chromium
npm run e2e
```

只跑 Chromium。L1 测空库冒烟（根页面、空列表、404、健康检查）。若本机 volume 里已经有 Run，空态断言会失败——那说明库不空，不要为此 seed。

停止栈仍用 `./scripts/down.sh`。
