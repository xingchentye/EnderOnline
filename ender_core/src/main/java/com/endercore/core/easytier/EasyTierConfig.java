/*
 * 本文件属于 EnderOnline 后端进程管理。
 *
 * 职责：EasyTier 进程的配置模型，负责把配置字段序列化并转换为命令行参数。
 */
package com.endercore.core.easytier;

import com.google.gson.annotations.SerializedName;
import java.util.ArrayList;
import java.util.List;

/**
 * EasyTier 配置模型。
 *
 * 本类既是 Gson 的序列化载体（字段名通过 SerializedName 与 EasyTier 的配置键对齐），
 * 也是命令行参数的生成器，两处对字段的解释必须保持一致。
 *
 * 设计约束：
 * 1. 全部字段为 public 且可变，属于有意的数据载体设计；任何持有本类实例的代码都不得把它
 *    当成不可变配置分发，修改字段会同时影响序列化结果与后续 toArgs 的输出。
 * 2. toArgs 只做「字段到参数」的映射，不校验取值合法性，非法组合由 EasyTier 进程自行报错。
 * 3. 默认 peers 指向 EasyTier 的公共中转节点，属于外部依赖；这些节点不可用时会直接影响联机成功率。
 * 4. 版本相关常量（rpcPort 默认值 58252）需与 EasyTierDownloader 的期望版本匹配。
 *
 * 线程安全性：本类不是线程安全的。字段无任何同步保护，toArgs 读字段的同时另一线程写字段
 * 会得到不一致的参数列表。跨线程使用时必须在外部完成同步或改为「构造后不再修改」。
 *
 * @since 1.0
 * @see com.multiplayer.ender.logic.PlatformHelper
 */
public class EasyTierConfig {

    /**
     * 网络名称，即 EasyTier 的网络标识（network name）。
     *
     * 允许为空字符串表示未设置，此时 toArgs 会跳过 --network-name 参数。
     */
    @SerializedName("network_name")
    public String networkName = "";

    /**
     * 网络密钥，同一网络内的节点必须一致才能互通。
     *
     * 允许为空字符串，为空时 toArgs 会跳过 --network-secret 参数。
     */
    @SerializedName("network_secret")
    public String networkSecret = "";

    /**
     * 对等节点列表，元素形如 tcp://host:port。
     *
     * 不能为 null（构造器会填充默认公共节点）；为 null 时 toArgs 会在遍历处抛 NullPointerException。
     */
    @SerializedName("peers")
    public List<String> peers = new ArrayList<>();

    /**
     * 监听地址列表，元素形如 udp://0.0.0.0:0，端口 0 表示由系统分配。
     *
     * 不能为 null；为 null 时 toArgs 会在遍历处抛 NullPointerException。
     */
    @SerializedName("listeners")
    public List<String> listeners = new ArrayList<>();

    /**
     * 是否禁用 TUN 设备，默认 true。
     *
     * 默认启用无 TUN 模式，因为桌面与 Android 都难以稳定申请到 TUN 设备。
     */
    @SerializedName("no_tun")
    public boolean noTun = true;

    /**
     * 压缩算法，默认 zstd。
     *
     * 允许为空字符串，为空时 toArgs 会跳过 --compression 参数。
     */
    @SerializedName("compression")
    public String compression = "zstd";

    /** 是否启用多线程转发，默认 true。 */
    @SerializedName("multi_thread")
    public boolean multiThread = true;

    /** 是否优先降低延迟而非带宽，默认 true。 */
    @SerializedName("latency_first")
    public boolean latencyFirst = true;

    /** 是否启用 KCP 代理，默认 true；KCP 用于在丢包链路上改善延迟表现。 */
    @SerializedName("enable_kcp_proxy")
    public boolean enableKcpProxy = true;

    /**
     * 是否只走 P2P、禁用中转，默认 true。
     *
     * NOTE: 字段名与命令行参数语义相反——字段为 true 时生成的是 --disable-p2p=false，
     * 即「不禁用 P2P」。修改该字段或参数映射时必须同时确认这一层反转。
     */
    @SerializedName("p2p_only")
    public boolean p2pOnly = true;

    /**
     * 是否以守护进程方式运行，默认 false。
     *
     * 默认 false 是因为进程生命周期由 EasyTierManager 持有，再交给系统守护会失去可观测性。
     */
    @SerializedName("daemon")
    public boolean daemon = false;

    /**
     * TCP 白名单端口，默认 "0" 表示不限制。
     *
     * 取值是逗号分隔的端口串；允许为空字符串，为空时跳过 --tcp-whitelist 参数。
     */
    @SerializedName("tcp_whitelist")
    public String tcpWhitelist = "0";

    /**
     * UDP 白名单端口，默认 "0" 表示不限制。
     *
     * 取值是逗号分隔的端口串；允许为空字符串，为空时跳过 --udp-whitelist 参数。
     */
    @SerializedName("udp_whitelist")
    public String udpWhitelist = "0";

    /**
     * RPC 端口，默认 58252，仅监听回环地址。
     *
     * 有效取值范围需大于 0；小于等于 0 时 toArgs 会跳过 --rpc-portal 参数。
     */
    @SerializedName("rpc_port")
    public int rpcPort = 58252;
    
    /**
     * 主机名，用于在 EasyTier 网络内标识本节点。
     *
     * 允许为空字符串，为空时跳过 --hostname 参数，由 EasyTier 使用系统默认名。
     */
    @SerializedName("hostname")
    public String hostname = "";

    /**
     * 日志级别，默认 INFO。
     *
     * 取值需为 EasyTier 支持的级别名。当前 toArgs 不会输出该字段，仅参与序列化。
     */
    @SerializedName("log_level")
    public String logLevel = "INFO";
    
    
    /**
     * 构造一份带默认中转节点与监听地址的配置。
     *
     * 默认 peers 为 EasyTier 公共节点，默认 listeners 为 UDP 与 TCP 的 0 端口（由系统分配）。
     * 传入 EasyTier 的参数由此构造，因此构造后应尽快设置 networkName 与 networkSecret。
     */
    public EasyTierConfig() {
        
        peers.add("tcp://public.easytier.top:11010");
        peers.add("tcp://public2.easytier.cn:54321");
        peers.add("tcp://8.148.29.206:11010");
        peers.add("tcp://39.108.52.138:11010");
        
        
        
        listeners.add("udp://0.0.0.0:0");
        listeners.add("tcp://0.0.0.0:0");
    }

    /**
     * 将配置转换为 EasyTier 的命令行参数列表。
     *
     * 空字符串字段会被静默跳过而不是报错，因此「漏填网络名」表现为参数缺失而非异常。
     *
     * 幂等性：本方法幂等且无副作用，只读取字段并生成新列表，重复调用结果相同。
     *
     * @return 命令行参数列表，永不为 null；顺序固定为网络、密钥、peers、listeners、开关、白名单、
     *         RPC、主机名
     * @throws NullPointerException 当 peers 或 listeners 为 null 时抛出（正常构造流程不会触发）
     */
    public List<String> toArgs() {
        List<String> args = new ArrayList<>();
        
        if (networkName != null && !networkName.isEmpty()) {
            args.add("--network-name");
            args.add(networkName);
        } else {
             
             
             
             
             
             
             
             
        }

        if (networkSecret != null && !networkSecret.isEmpty()) {
            args.add("--network-secret");
            args.add(networkSecret);
        }

        for (String peer : peers) {
            args.add("-p");
            args.add(peer);
        }

        for (String listener : listeners) {
            args.add("-l");
            args.add(listener);
        }

        if (noTun) args.add("--no-tun");
        
        if (compression != null && !compression.isEmpty()) {
            args.add("--compression=" + compression);
        }
        
        if (multiThread) args.add("--multi-thread");
        if (latencyFirst) args.add("--latency-first");
        if (enableKcpProxy) args.add("--enable-kcp-proxy");
        if (p2pOnly) args.add("--disable-p2p=false"); 
        
        if (daemon) {
            args.add("-d"); 
        }
        
        if (tcpWhitelist != null && !tcpWhitelist.isEmpty()) {
            args.add("--tcp-whitelist=" + tcpWhitelist);
        }
        
        if (udpWhitelist != null && !udpWhitelist.isEmpty()) {
            args.add("--udp-whitelist=" + udpWhitelist);
        }
        
        if (rpcPort > 0) {
            args.add("--rpc-portal");
            args.add("127.0.0.1:" + rpcPort);
        }

        if (hostname != null && !hostname.isEmpty()) {
            args.add("--hostname");
            args.add(hostname);
        }

        return args;
    }
}
