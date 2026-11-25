# Sertraline

超天物品管理系统服务 (Liminal Skyline v4.0 服务)

ChoTen Item management system service (Liminal Skyline v4.0 Service)
<img width="2003" height="1118" alt="图片1" src="https://github.com/user-attachments/assets/6a1832f3-a0e2-480b-a823-1876fffe21f8" />

<img width="1344" height="768" alt="image" src="https://github.com/user-attachments/assets/c44b41ae-d110-4e9e-bf96-9cbc32a0d7d9" />

<div align="center">
  
<img width="2010" height="1125" alt="42ddc36e-7fe7-4b43-b927-f20c91ff1bd0" src="https://github.com/user-attachments/assets/258bc7c3-6dcc-4a24-8ac9-9ee358f62c89" />

<img alt="AVATAR" src="https://github.com/user-attachments/assets/4e876740-8759-4b0d-a42c-7a8cf9692f9d" />

<p align="center">
  <a href="https://github.com/zzzyyylllty/Sertraline-Hydrochloride/commits/main/">
    <img src="https://img.shields.io/github/last-commit/zzzyyylllty/Sertraline-Hydrochloride?logo=artstation&style=for-the-badge"/>
  </a>
  <a href="https://github.com/zzzyyylllty/Sertraline-Hydrochloride/issues">
    <img src="https://img.shields.io/github/issues/zzzyyylllty/Sertraline-Hydrochloride?style=for-the-badge&logo=slashdot"/>
  </a>
  <a href="https://github.com/zzzyyylllty/Sertraline-Hydrochloride/releases">
    <img src="https://img.shields.io/github/release/zzzyyylllty/Sertraline-Hydrochloride?style=for-the-badge&color=CC66FF&logo=ionic"/>
  </a>
</p>
<p align="center">
  <a href="https://qun.qq.com/universal-share/share?ac=1&authKey=EJ%2FBIgYus1kDxqSE5WHvAMyC7EaBqcpEFN7fBGtNFRGe6ZzjX9uECORltHB1q6Um&busi_data=eyJncm91cENvZGUiOiI4NTg4Mjc1MjMiLCJ0b2tlbiI6ImpYYlV6WEJnYXJadTNUd3pnOWU1c3ZsSFh1Z3h2UjFuc3JOb3d5VVhtRWs5bm9BKzZoZzhxckNsenhydmQyQ2oiLCJ1aW4iOiIzODI0NjM2MTk0In0%3D&data=PCCbZvFVCR3De_3tA0iMGgfjVc1xN4-yJslk2O-X4rYSvNkYvAFJymCjz9Sq6cNA1PLe_zJYmCHRRRK0FwNuHw&svctype=4&tempid=h5_group_info">
    <img src="https://img.shields.io/badge/QQ_Group-858827523-blue?style=for-the-badge"/>
  </a>
  <a href="https://discord.com/invite/VHs958jJXj">
    <img src="https://img.shields.io/discord/1272636652447338561?style=for-the-badge&label=DISCORD&color=%23ff66cc"/>
  </a>
  <a href="https://deepwiki.com/zzzyyylllty/Sertraline-Hydrochloride">
    <img src="https://img.shields.io/badge/Ask-DEEPWIKI-aqua?style=for-the-badge"/>
  </a>
  <a href="https://afdian.com/a/liminalskyline">
    <img src="https://img.shields.io/badge/Donate-AFDIAN-dd66ff?style=for-the-badge"/>
  </a>

</p>

**Standard and Spigot target the Minecraft 1.21.4 API on Java 21. Legacy targets Minecraft 1.12.2 on Java 8.**

</div>

## AI Questions

This project is used Claude and Deepseek for performance optimization.

But the basic part is 100% humanmade!

## Donate!

Click here to donate via [Afdian](https://afdian.com/a/liminalskyline).

## Item Plugins Contrast

<img width="2000" height="1132" alt="855d861e-3e76-4b79-9fff-8fdb14aec058" src="https://github.com/user-attachments/assets/fccf866e-72aa-4156-b6f9-61653f78fefe" />

## Special Thanks

### Thanks for other Projects

* [TabooLib](https://github.com/TabooLib/taboolib) - plugin's framework.
* [Ratziel](https://github.com/TheFloodDragon/Ratziel-Beta) - some visual feature's reference.
* [CraftEngine](https://github.com/Xiao-MoMi/craft-engine) - some component feature's reference.
* [TrMenu](https://github.com/CoderKuo/TrMenu) - packet feature's reference.

### Thanks for other Developers

* [JhqwqMc](https://github.com/jhqwqmc) - some component feature's contributor.
* [ChengZhiMeow](https://github.com/ChengZhiMeow/) - visual feature's reference.
* [TheFloodDragon](https://github.com/TheFloodDragon) - ratziel's author.
* [Xiao-MoMi](https://github.com/Xiao-MoMi/) - craft-engine's author, craft-engine is this plugin's reference.

## Build and test

Install JDK 21. Set JAVA_HOME to JDK 21, or set JDK21_HOME to its location when using a newer launcher JDK. Use gradlew.bat on Windows.

| Command | Output |
| --- | --- |
| ./gradlew build | Standard and Free jars under plugin/build/libs |
| ./gradlew -Pplatform=spigot build | Spigot jars under plugin/build-spigot/libs |
| ./gradlew -Pplatform=legacy12 build | Legacy jars under plugin/build-legacy12/libs |
| ./gradlew :plugin:buildAll | All three platform builds and their tests |
| `./gradlew tabooBuildApi` | `project/common/build/libs/Sertraline-X.X.X.X-api.jar` |

Each platform builds against its own server API. The Legacy build targets Java 8 bytecode and omits modern recipe and component features. The build itself uses JDK 21 for every platform.

## Use as a dependency

Add Sertraline from Maven Central. For Gradle Kotlin DSL, use the following.

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    implementation("io.github.zzzyyylllty.sertraline:Sertraline:3.13.2.2")
}
```

For Maven, add this dependency under `<dependencies>` in `pom.xml`.

```xml
<dependency>
    <groupId>io.github.zzzyyylllty.sertraline</groupId>
    <artifactId>Sertraline</artifactId>
    <version>3.13.2.2</version>
</dependency>
```

Install the matching Sertraline runtime JAR in the server's `plugins/` directory and declare `depend: [Sertraline]` in your plugin's `plugin.yml`.

For item lookups and construction, use SertralineItemAPI. See [API documentation](docs/SertralineAPI-en.md), [Template Syntax (Chinese)](docs/Template-zh.md), and [Default Combat Pack](docs/DefaultCombatPack-en.md).

## Publish to Maven Central

Set the release version in `gradle/libs.version.toml` and provide the Central Portal token and GPG signing credentials through the following environment variables. The Gradle Wrapper maps the two `CENTRAL_SERTRALINE_` variables to the publishing plugin's Gradle properties.

| Environment variable | Value |
| --- | --- |
| `CENTRAL_SERTRALINE_USERNAME` | Central Portal token username |
| `CENTRAL_SERTRALINE_PASSWORD` | Central Portal token password |
| `CENTRAL_SERTRALINE_GPG_KEY` | Fingerprint of a signing key in the local GnuPG keyring |
| `ORG_GRADLE_PROJECT_signingInMemoryKey` | ASCII-armored GPG private key |
| `ORG_GRADLE_PROJECT_signingInMemoryKeyPassword` | GPG key passphrase, if set |
| `ORG_GRADLE_PROJECT_signingInMemoryKeyId` | GPG key ID when selecting a signing subkey |

For local signing, set `CENTRAL_SERTRALINE_GPG_KEY` to the fingerprint shown by `gpg --list-secret-keys --keyid-format long`. Gradle uses GnuPG on `PATH`, and the GPG agent can prompt for the passphrase. If an in-memory key is supplied, Gradle uses that key.

Run `./gradlew publishToMavenCentral` from the repository root with the default Paper platform, then publish the validated deployment in Central Portal. Use `gradlew.bat` on Windows.
