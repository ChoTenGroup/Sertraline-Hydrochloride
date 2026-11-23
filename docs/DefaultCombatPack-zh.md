# 默认战斗属性包

本包只增加资源配置，不改 Kotlin/Java 源码。属性由现有 AttributeAPI 汇总，伤害规则由同步全局监听器执行。

## 安装与重载

首次安装会随默认资源释放。已有服务器不会自动覆盖配置，需要合并或复制以下文件到 `plugins/Sertraline/` 下的对应目录：

- `project/common-files/src/main/resources/attributes/default.yml`：把 `attributes` 下的 `sertraline:*` 定义合并到现有属性文件；保留服务器已有定义，避免同名 ID 重复。
- `project/common-files/src/main/resources/global-listeners/default-combat.yml`
- `project/common-files/src/main/resources/workspace/default/default_combat_weapons.yml`
- `project/common-files/src/main/resources/workspace/default/default_combat_armor.yml`

执行 `/sertraline reload`，确认没有属性或脚本错误。使用 `/sertralineitem give <物品ID>` 获取样例。需要启用 `attribute.enable`。
监听器使用 GraalJS。`config.yml` 中的 `script.graaljs.allow-java-type: true` 允许 `Java.type` 查找 Java、Bukkit 和插件类；已有配置缺少该项时默认开启，配置同步会补齐此项。
设为 `false` 会禁止通过类名查找类；修改后执行 `/sertraline reload`，下一次脚本执行会按新设置重建缓存上下文。本包监听器需要此项开启。
`defaultData` 已注入的 `Bukkit`、`AttributeAPI`、`bukkitPlugin`、`scheduler` 等变量可直接使用，不需要 `Java.type`；此开关控制额外的类名查找，不影响这些变量的注入。
关闭 `attribute.enable` 后，两项监听器均跳过处理，包括已有进攻属性快照的箭矢。
移走监听器文件并重载可停用本包伤害规则。已有其他战斗插件时，应根据需要选择一套伤害处理规则，避免重复结算。

## 属性与公式

属性 ID 均以 `sertraline:` 开头，与旧版 `crit_chance`、`crit_damage`、`magic_damage` 等内置属性相互独立。

| 属性 ID（省略命名空间） | 默认值 | 含义 |
| --- | --- | --- |
| physical_defense / magic_defense / fire_defense / poison_defense / fall_defense | 0 | 对应类型的防御，范围 0–10000 |
| physical_damage / magic_damage / fire_damage / poison_damage | 0 | 攻击附加伤害，范围 0–10000 |
| critical_chance | 0 | 暴击概率，0–1，0.25 表示 25% |
| critical_bonus | 0.5 | 暴击额外倍率，0–10，0.5 表示暴击造成 150% 伤害 |

每个伤害分量独立计算：`减伤后伤害 = 分量伤害 × 100 / (100 + 对应防御)`。
防御 100 减少 50%，防御 300 减少 75%，不会变为负伤害。摔落防御 100 会把原版摔落基础伤害减半。

攻击的原始伤害与四种附加伤害分别减伤后求和；本包暴击触发时，再乘以 `1 + critical_bonus`。
例如，原始物理伤害 10、附加火焰伤害 6，目标物防 100、火防 50，结算为 `10/2 + 6/1.5 = 9`；额外暴击倍率 0.5 时为 13.5。
这修改的是 Bukkit 事件的基础伤害，原版护甲、抗性、附魔等仍会继续参与计算，因此实际扣血可能更少。
原版跳劈暴击仍保留，可与本包暴击叠加。

## 伤害类型

| 防御类型 | Bukkit DamageCause |
| --- | --- |
| 物理 | ENTITY_ATTACK、ENTITY_SWEEP_ATTACK、PROJECTILE、CONTACT、ENTITY_EXPLOSION、BLOCK_EXPLOSION、FALLING_BLOCK、FLY_INTO_WALL、THORNS |
| 魔法 | MAGIC、DRAGON_BREATH、SONIC_BOOM、WITHER |
| 火焰 | FIRE、FIRE_TICK、LAVA、HOT_FLOOR |
| 中毒 | POISON |
| 摔落 | FALL |

虚空、饥饿、溺水、CUSTOM 等未列出的原因保持原有结算。药水直接命中由其伤害事件原因分类，不能仅凭投掷者手中的武器改变伤害类型。
火焰和中毒附伤是本次攻击的即时伤害分量，不自动施加燃烧或药水效果；毒附伤可以致死。

只有生物直接近战、横扫，以及箭/三叉戟的 PROJECTILE 命中结算附伤和暴击。荆棘、持续燃烧、中毒和爆炸只减伤，不触发新的附伤或暴击。
箭/三叉戟发射时通过实体 PDC 保存六项进攻属性，命中时读取快照；切换武器、退出游戏或射手消失不会改变已保存的数值。
投掷三叉戟快照读取的是发射时射手已装备的属性，不读取投出后实体携带的物品。
没有快照的投射物只结算原始伤害的防御。雪球、鸡蛋和药水不获得武器附伤。

脚本不调用 `entity.damage()`，只修改当前事件一次，不产生递归伤害。已取消、零基础伤害、盾牌完全格挡的事件保持不变。
吸收生命使最终伤害为零时，仍先加入附伤，再由 Bukkit 重新计算吸收；不能把吸收生命当成完全格挡。
默认附伤为固定数值，不额外按近战蓄力或弓蓄力缩放；可在监听器中调整平衡。

## 默认物品

| ID | 名称 | 加成 |
| --- | --- | --- |
| combat_guard_sword | 守卫长剑 | 物伤 +5，物防 +10 |
| combat_assassin_dagger | 刺客短刃 | 物伤 +3，暴击概率 +25%，暴击加成 +75% |
| combat_arcane_blade | 秘法之刃 | 魔伤 +8，魔防 +15 |
| combat_ember_sword | 余烬长剑 | 火伤 +7，火防 +20 |
| combat_venom_blade | 淬毒短刃 | 毒伤 +6，暴击概率 +10% |
| combat_ranger_bow | 游侠长弓 | 物伤 +4，暴击概率 +15%，暴击加成 +25% |
| combat_sentinel_helmet | 哨卫头盔 | 物防 +20，魔防 +10 |
| combat_runic_chestplate | 符文胸甲 | 物防 +25，魔防 +35 |
| combat_ember_leggings | 余烬护腿 | 物防 +15，火防 +50 |
| combat_feather_boots | 轻羽长靴 | 摔落防御 +100，物防 +10 |
| combat_antidote_helmet | 净毒兜帽 | 毒防 +75，魔防 +15 |
| combat_ward_shield | 四象守护盾 | 物/魔/火/毒防各 +20 |

武器只在主手生效，防具只在对应装备栏生效，守护盾只在副手生效。头盔与兜帽是两个可选物品，没有隐藏套装效果。
物品使用稳定 ID 的 `minecraft:add_value` 修饰符，暴击加成与基础 0.5 相加：刺客短刃的最终暴击倍率是 `1 + 0.5 + 0.75 = 2.25`。
物品 Lore 中的数值表示该物品提供的加成。

## 验证

运行 `python docs/tests/default_combat_test.py`（需要 Python、PyYAML 与 Node.js）。
测试读取真实 YAML 并执行其中的监听脚本，验证类型分流、混合伤害、防御、暴击、取消/零伤害与箭矢快照。
这是模拟 Bukkit 对象的脚本测试；服务器最终伤害、装备读取与其他插件兼容性仍需在目标服务器验证。

设置 `COMBAT_TEST_CLASSPATH` 为以系统路径分隔符连接的 GraalJS/Polyglot/Truffle、Bukkit/Paper API、Guava 及 API 所需 Adventure 依赖后，
可运行 `python docs/tests/default_combat_test.py --runtime`（另需 JDK）。这会使用真实 GraalJS 和 Bukkit API 检查类名查找、脚本初始化、伤害方法重载及 DOUBLE PDC 写入类型。
此项检查仅模拟属性引擎的启用标志，不启动 Minecraft 服务器。
