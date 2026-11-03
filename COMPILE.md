# Hướng dẫn Compile Auto Tree Farmer

## Yêu cầu
- Java 21+ (JDK, không phải JRE)
- Git

## Các bước

### 1. Cài Java 17+
Tải tại: https://adoptium.net/

Kiểm tra:
```
java -version
```

### 2. Clone / Giải nén project
Đặt thư mục project vào nơi nào đó dễ truy cập.

### 3. Compile bằng Gradle Wrapper
Mở terminal trong thư mục project rồi chạy:

**Windows:**
```
gradlew.bat build
```

**Linux / macOS:**
```
chmod +x gradlew
./gradlew build
```

Lần đầu sẽ tải về Gradle + Minecraft dev tools (~500MB), mất vài phút.

### 4. Lấy file .jar
Sau khi build thành công, file .jar nằm tại:
```
build/libs/auto-tree-farmer-1.0.0.jar
```

### 5. Cài vào Minecraft
1. Cài **Fabric Loader 0.19.3** cho **Minecraft 1.21.11**
   - https://fabricmc.net/use/installer/
2. Cài **Fabric API 0.139.5+1.21.11** (bỏ vào thư mục mods)
   - https://modrinth.com/mod/fabric-api
3. Copy `auto-tree-farmer-1.0.0.jar` vào thư mục `.minecraft/mods/`

## Sử dụng

| Phím | Tác dụng |
|------|----------|
| **K** | Bật / Tắt Auto Tree Farmer |

Có thể đổi phím tại: Options → Controls → Auto Tree Farmer

## Lưu ý
- Đứng gần khu farm trước khi bật (bán kính quét 8 block)
- Cần có **Oak Sapling** và **Bone Meal** trong hotbar
- Nên mang theo đồ ăn để tự hồi phục
- Nếu không thấy cây mọc sau 80 lần bone meal → mod tự bỏ qua ô đó
