# 合并: base.apk模板 + classes.dex + assets/xposed_init + lib/*/libdexkit.so -> cmhook-unsigned.apk
# 规范: resources.arsc 与 .so 必须 ZIP_STORED (Android 11+ 安装要求 / mmap 加载)
import zipfile, shutil, os

os.chdir(os.path.dirname(os.path.abspath(__file__)))

src = 'base.apk'
dst = 'cmhook-unsigned.apk'
if os.path.exists(dst):
    os.remove(dst)

zin = zipfile.ZipFile(src, 'r')
zout = zipfile.ZipFile(dst, 'w')
for item in zin.infolist():
    if item.filename in ('classes.dex',):
        continue  # 旧dex丢弃, 换新的
    data = zin.read(item.filename)
    comp = zipfile.ZIP_STORED if item.filename == 'resources.arsc' else item.compress_type
    zi = zipfile.ZipInfo(item.filename, date_time=item.date_time)
    zi.compress_type = comp
    zi.external_attr = item.external_attr
    zout.writestr(zi, data)

dex = open('dexout/classes.dex', 'rb').read()
zi = zipfile.ZipInfo('classes.dex', date_time=(2026, 8, 29, 0, 0, 0))
zi.compress_type = zipfile.ZIP_DEFLATED
zout.writestr(zi, dex)

xinit = open(os.path.join('assets', 'xposed_init'), 'rb').read()
zi = zipfile.ZipInfo('assets/xposed_init', date_time=(2026, 8, 29, 0, 0, 0))
zi.compress_type = zipfile.ZIP_STORED
zi.external_attr = 0o644 << 16
zout.writestr(zi, xinit)

# DexKit native 库 (4 ABI), 全部 ZIP_STORED + 由 zipalign 对齐
for abi in ('arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64'):
    so_path = os.path.join('libs', 'dexkit_aar', 'jni', abi, 'libdexkit.so')
    if not os.path.exists(so_path):
        continue
    zi = zipfile.ZipInfo('lib/%s/libdexkit.so' % abi, date_time=(2026, 8, 29, 0, 0, 0))
    zi.compress_type = zipfile.ZIP_STORED
    zi.external_attr = 0o644 << 16
    zout.writestr(zi, open(so_path, 'rb').read())
    print('packed lib/%s/libdexkit.so' % abi)

zout.close(); zin.close()
print('merged ->', dst, os.path.getsize(dst), 'bytes')
