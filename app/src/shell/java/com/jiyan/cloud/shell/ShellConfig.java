package com.jiyan.cloud.shell;

/**
 * 壳常量。壳只负责：加载组件、组件热更新、最小化进度提示。
 * 业务（云更新文件、UI、卡密等）全部在组件里。
 */
public final class ShellConfig {

    private ShellConfig() {}

    /* ================= 站点 ================= */

    /** 组件库接口基址（主站 HTTPS，Java 无 JVM 限制） */
    public static final String API_BASE = "https://jiyan.xn--0try77c6um82w.cn/api/JiYan/";

    /** 组件签名公钥（ECDSA P-256，X.509 DER base64；私钥只在服务端） */
    public static final String COMPONENT_PUBKEY =
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEuP8/KOyrcgczcqIcBcjGHl+3w3NgcwPN/62y2T+7liDzBrnQkna+qNYGMqIcuylTePQat0kXhf2e94tR12oqwg==";

    /** 组件实现类（组件 dex 内必须存在） */
    public static final String COMPONENT_ENTRY = "com.jiyan.cloud.comp.ComponentImpl";

    /* ================= 版本 ================= */

    /** 壳版本，参与 min_shell 判断与上报 */
    public static final int SHELL_VERSION = 1;
    /** 壳的构建号，只用于界面与日志标识，便于确认手机跑的是哪一版。 */
    public static final int SHELL_BUILD = 26;

    public static final String SHELL_NAME = "JiYan_CloudShell";

    /* ================= 目录 ================= */

    public static final String ROOT_DIR = "JiYan_Cloud";
    public static final String SHELL_DIR = "shell";
    public static final String STATE_FILE = "state.json";
    public static final String COMPONENTS_DIR = "components";

    public static final String LOG_FILE = "JiYan_shell.log";
    public static final long LOG_MAX_BYTES = 1024 * 1024;

    /* ================= 配置解密（与后台生成一致） ================= */

    public static final String CONFIG_KEY =
            "HCJKOH6LXCepfuyhu8AFxJR39DUB2SyLLkiQJ9j5z7Y=";

    /* ================= 网络 ================= */

    public static final int CONNECT_TIMEOUT = 15000;
    public static final int READ_TIMEOUT = 30000;
    public static final String USER_AGENT = "JiYan_CloudShell/" + SHELL_BUILD;
}
