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

只跑 Chromium。L1 测空库冒烟（运行列表、空态、404、健康检查）。L2 用路由拦截伪造 API，测导航、分页、轮询合并、错误态，以及「未知不显示 0 / 截断可见 / 无定位记录」。若本机 volume 里已经有 Run，L1 空态断言会失败——那说明库不空，不要为此 seed。

停止栈仍用 `./scripts/down.sh`。
