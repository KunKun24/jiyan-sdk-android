# 贡献指南 / Contributing

1. Fork，分支 `fix/xxx` 或 `feat/xxx`，一个 PR 只做一件事。
2. 必须通过 `bash scripts/build-check.sh`。
3. 不引入 AndroidX / 第三方运行时依赖（壳要保持最小）。
4. 不改动 `spi/Component` 的二进制兼容性；确需改动请在 PR 里说明迁移方案。
5. 不提交真实密钥、账号、用户数据或业务组件。
6. 提交即表示同意以 MIT 授权你的贡献。
