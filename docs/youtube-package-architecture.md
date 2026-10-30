# هيكل YouTube المقترح

## الهدف

إعادة هيكلة مساحة `app.morphe.patches.youtube` بحيث تصبح:
- قابلة للصيانة
- سهلة الفحص
- منطقية من ناحية الميزة
- أقل تشتتًا وتداخلًا
- مناسبة للتوسع دون `misc` و`shared` غير المنظمين

## القاعدة الأساسية

تقسيم المشروع حسب الميزة لا حسب النوع الفني فقط.

- `features/` : كل ميزة end-user مستقلة
- `platform/` : أدوات أساسية مثل Litho و Protobuf و hooks
- `shared/` : utilities مشتركة فقط
- `core/` : التوافق والتسجيل والتحميل

## الهيكل النهائي المقترح

```text
patches/src/main/kotlin/app/morphe/patches/youtube/
├─ core/
│  ├─ compatibility/
│  │  ├─ VersionCheckPatch.kt
│  │  ├─ FeatureFlagsPatch.kt
│  │  └─ YoutubeCompatibility.kt
│  ├─ bootstrap/
│  │  ├─ YoutubePatchRegistry.kt
│  │  ├─ YoutubePatchLoader.kt
│  │  └─ PatchEntry.kt
│  └─ config/
│     ├─ YoutubeConfig.kt
│     └─ YoutubeConstants.kt
│
├─ platform/
│  ├─ litho/
│  │  ├─ LithoFilterPatch.kt
│  │  ├─ LithoContextPatch.kt
│  │  ├─ LithoComponentFilter.kt
│  │  └─ LithoFingerprint.kt
│  ├─ protobuf/
│  │  ├─ ProtobufPatch.kt
│  │  ├─ BufferFilterPatch.kt
│  │  └─ ProtobufFingerprint.kt
│  ├─ hooks/
│  │  ├─ HookRegistry.kt
│  │  ├─ HookSupport.kt
│  │  └─ HookUtils.kt
│  └─ resources/
│     └─ ResourceHelpers.kt
│
├─ shared/
│  ├─ utils/
│  │  ├─ ViewUtils.kt
│  │  ├─ StringUtils.kt
│  │  └─ ResourceUtils.kt
│  ├─ settings/
│  │  ├─ YoutubeSettings.kt
│  │  ├─ PreferencePatch.kt
│  │  └─ SettingsKeys.kt
│  ├─ models/
│  │  └─ YoutubeState.kt
│  └─ analytics/
│     └─ PatchMetrics.kt
│
├─ features/
│  ├─ ads/
│  │  ├─ AdBlockPatch.kt
│  │  ├─ AdLayoutPatch.kt
│  │  └─ AdFingerprint.kt
│  ├─ captions/
│  │  ├─ CaptionViewportPatch.kt
│  │  ├─ CaptionStylePatch.kt
│  │  ├─ CaptionSettingsPatch.kt
│  │  └─ CaptionFingerprint.kt
│  ├─ comments/
│  │  ├─ CommentFilterPatch.kt
│  │  ├─ CommentActionPatch.kt
│  │  └─ CommentFingerprint.kt
│  ├─ feed/
│  │  ├─ FeedFilterPatch.kt
│  │  ├─ FeedLayoutPatch.kt
│  │  ├─ ShortsFeedPatch.kt
│  │  └─ FeedFingerprint.kt
│  ├─ player/
│  │  ├─ PlayerControlsPatch.kt
│  │  ├─ PlaybackPatch.kt
│  │  ├─ VideoIdPatch.kt
│  │  ├─ QualityPatch.kt
│  │  └─ PlayerFingerprint.kt
│  ├─ search/
│  │  ├─ SearchFilterPatch.kt
│  │  ├─ SearchLayoutPatch.kt
│  │  └─ SearchFingerprint.kt
│  ├─ subscriptions/
│  │  ├─ SubscriptionPatch.kt
│  │  └─ SubscriptionFingerprint.kt
│  ├─ interaction/
│  │  ├─ InteractionPatch.kt
│  │  ├─ ButtonPatch.kt
│  │  └─ InteractionFingerprint.kt
│  └─ layout/
│     ├─ FullscreenPatch.kt
│     ├─ MiniplayerPatch.kt
│     ├─ ToolbarPatch.kt
│     └─ LayoutFingerprint.kt
│
└─ YoutubePatchBundle.kt
```

## مبدأ التسمية

- `*Patch.kt` : patch رئيسي
- `*Fingerprint.kt` : fingerprint أو تحديد الكود
- `*Hook.kt` : hook إضافي خاص بالمنطق
- `*Settings.kt` : إعدادات الميزة
- `*Utils.kt` : helper صغير
- `*Registry.kt` : تسجيل الحزم أو الواجهات

## مبدأ التنظيم

### 1) الميزات مستقلة
كل ميزة تعيش داخل `features/<feature>/` وليس تحت `layout` أو `video` أو `misc`.

### 2) `shared` لا يكون dumping ground
يحتوي فقط على:
- utils
- settings
- constants
- shared models
- common metrics

### 3) `platform` يحوي التنفيذ التقني
لا يتم وضع منطق يخص YouTube داخل `platform` إلا ما هو فني عام مثل:
- Litho
- Protobuf
- Hook infrastructure
- resource helpers

### 4) `core` يحوي الجسر بين النظام والـ patches
يجب أن يحوي:
- bootstrap
- compatibility
- registry
- bundle

## المعايير العملية

### فئات غير مسموح وضعها في `misc`
- `layout`
- `video`
- `interaction`
- `ad`
- `search`
- `comments`
- `captions`
- `player`
- `feed`
- `shorts`

إذا كانت الميزة لها اسم واضح، فيجب أن يكون لها مجلد خاص.

## مثال تحويل عملي

بدل:

```text
youtube/layout/captions/CaptionViewportPatch.kt
youtube/video/format/videoFormatPatch.kt
youtube/misc/playservice/versionCheckPatch.kt
```

يصبح:

```text
youtube/features/captions/CaptionViewportPatch.kt
youtube/features/player/VideoFormatPatch.kt
youtube/core/compatibility/VersionCheckPatch.kt
```

## خطة الترحيل المرحلي

### المرحلة 1: إنشاء الواجهة الجديدة
- إنشاء المجلدات الجديدة
- إضافة `README` توضيحي لكل feature group
- التأكد أن لا توجد ملفات مكسورة

### المرحلة 2: نقل ملفات الميزات المهمة
- `captions`
- `player`
- `feed`
- `search`
- `comments`
- `ads`

### المرحلة 3: تنظيف `shared` و `misc`
- نقل كل utility حقيقي إلى `shared/utils`
- حذف المجلدات القديمة بعد التأكد من سلامة الاستيراد

### المرحلة 4: إضافة Contracts و Registries
- `YoutubePatchRegistry`
- `PatchEntry`
- `CompatibilityLayer`

### المرحلة 5: تقليل الاعتماد بين المجلدات
- كل feature له واجهاتها الخاصة
- `shared` يكون محدودًا بالـ helpers المشتركة فقط

## توصية هامة

لا نوصي بحذف البنية الحالية دفعة واحدة داخل مستودع كبير مثل هذا، لأن ذلك قد يكسر البناء أو الاستيرادات بشكل كبير.

الأفضل هو:
- إنشاء هيكل جديد
- نقل الميزات تدريجيًا
- تحديث imports بشكل انتقائي
- التأكد من نجاح build بعد كل مرحلة

## التوصية النهائية

أفضل بنية فعليًا لهذا المستودع هي:

```text
youtube/
  core/
  platform/
  shared/
  features/
  YoutubePatchBundle.kt
```

وهذا هو الشكل الأنسب للمشروع الحالي، لأنه يجمع بين التنظيم، التوافق، وسهولة التوسع دون أن يُصبح `misc` مكدسًا غير منظم.

## الخلاصة

الحل الأمثل ليس “تغيير أسماء الملفات فقط”، بل هو:
- فصل الميزة عن التقنية
- جعل `framework` و `platform` مستقلين
- تقليل `misc` إلى الصفر أو أقرب ما يمكن
- استخدام `features/` كعامل رئيسي للهيكلة

هذا هو أفضل إعادة هيكلة عملية ومناسبة للمستودع الحالي.
