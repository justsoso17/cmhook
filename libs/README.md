# libs/

构建前把依赖放到这里（仓库不提供二进制）：

```
libs/
├── dexkit.jar                 ← DexKit 2.2.0 AAR 的 classes.jar
├── kotlin-stdlib.jar          ← org.jetbrains.kotlin:kotlin-stdlib:1.5.0
├── flatbuffers.jar            ← com.google.flatbuffers:flatbuffers-java:23.5.26
└── dexkit_aar/jni/<abi>/libdexkit.so   ← DexKit AAR 的 jni/*（4 个 ABI）
```

DexKit 2.2.0 AAR 下载：
`https://maven-central.storage-download.googleapis.com/maven2/org/luckypray/dexkit/2.2.0/dexkit-2.2.0.aar`
