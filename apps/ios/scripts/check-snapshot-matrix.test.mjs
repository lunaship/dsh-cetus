import assert from "node:assert/strict"
import test from "node:test"
import { declarations, missingDeclarations, requiredFor } from "./check-snapshot-matrix.mjs"

const full = "func testOne() { matrix(\"1_1_demo\") { page(language: $0) } }\nfunc matrix<V: View>(_ scene: String, make: (String) -> V) {\n    shot(scene, appearance: .light, language: \"zh-Hans\", large: false, make: { make(\"zh-Hans\") })\n    shot(scene, appearance: .light, language: \"zh-Hans\", large: true, make: { make(\"zh-Hans\") })\n    shot(scene, appearance: .light, language: \"en\", large: false, make: { make(\"en\") })\n    shot(scene, appearance: .light, language: \"en\", large: true, make: { make(\"en\") })\n    shot(scene, appearance: .dark, language: \"zh-Hans\", large: false, make: { make(\"zh-Hans\") })\n    shot(scene, appearance: .dark, language: \"zh-Hans\", large: true, make: { make(\"zh-Hans\") })\n    shot(scene, appearance: .dark, language: \"en\", large: false, make: { make(\"en\") })\n    shot(scene, appearance: .dark, language: \"en\", large: true, make: { make(\"en\") })\n    shot(scene, appearance: .light, language: \"zh-Hans\", large: false, named: \"reduce-transparency\", make: { make(\"zh-Hans\") })\n    shot(scene, appearance: .light, language: \"zh-Hans\", large: false, named: \"increase-contrast\", make: { make(\"zh-Hans\") })\n}\nfunc shot<V: View>(_ scene: String, appearance: UIUserInterfaceStyle, language: String, large: Bool, named: String? = nil, make: () -> V) {\n    assertSnapshot(of: make(), as: .image, named: named ?? (large ? \"large\" : \"default\"), testName: snapshotName(scene, appearance: appearance, language: language))\n}\nfunc snapshotName(_ scene: String, appearance: UIUserInterfaceStyle, language: String) -> String {\n    \"Snapshot_\\(scene)_\\(appearance == .dark ? \"dark\" : \"light\")_\\(language == \"en\" ? \"en\" : \"zh\")\"\n}"
const welcome = "func testWelcomeLight() { assertSnapshot(of: view, as: .image, named: \"light\") }\nassertSnapshot(of: view, as: .image, named: \"reduce-transparency\", testName: \"Snapshot_1_2_welcome_light_zh\")\nassertSnapshot(of: view, as: .image, named: \"increase-contrast\", testName: \"Snapshot_1_2_welcome_light_zh\")\nassertSnapshot(of: view, as: .image, named: \"large\", testName: \"Snapshot_1_2_welcome_a11y_light_en\")\nassertSnapshot(of: view, as: .image, named: \"large\", testName: \"Snapshot_1_2_welcome_a11y_dark_en\")"
const component = "func testChip() {\n    assertSnapshot(of: view, as: .image, named: \"light\")\n    assertSnapshot(of: view, as: .image, named: \"dark\")\n    assertSnapshot(of: view, as: .image, named: \"long\")\n    assertSnapshot(of: view, as: .image, named: \"disabled\")\n    assertSnapshot(of: view, as: .image, named: \"large\")\n    assertSnapshot(of: view, as: .image, named: \"reduce-transparency\")\n    assertSnapshot(of: view, as: .image, named: \"increase-contrast\")\n}"
const wide = "func testPortrait() { shot(\"wide_portrait\") }\nfunc shot(_ scene: String) {\n    render(named: \"default\")\n    render(named: \"reduce-transparency\")\n    render(named: \"increase-contrast\")\n}\nfunc render(named: String) {\n    assertSnapshot(of: image, as: .image(precision: 0.995), named: named, testName: \"Snapshot_\\(scene)_light_zh\")\n}"

test("完整矩阵声明通过", () => {
  assert.deepEqual(missingDeclarations([{ name: "NewTaskSnapshotTests.swift", source: full }]), [])
})

test("缺少增强对比度时失败", () => {
  const source = full.replace("increase-contrast", "default")
  const missing = missingDeclarations([{ name: "NewTaskSnapshotTests.swift", source }])
  assert.ok(missing.some((item) => item.endsWith("increase-contrast")))
})

test("IP 地址不是 scene", () => {
  const source = full + "\nlet address = \"192.0.2.10:18640\""
  assert.equal(requiredFor("NewTaskSnapshotTests.swift", source).some((item) => item.includes("192")), false)
})

test("Welcome 只要求五个例外", () => {
  assert.deepEqual(missingDeclarations([{ name: "WelcomeSnapshotTests.swift", source: welcome }]), [])
})

test("组件缺少英文时失败", () => {
  assert.deepEqual(missingDeclarations([{ name: "ComponentSnapshotTests.swift", source: component }]), ["ComponentSnapshotTests.swift: testChip.en"])
})

test("宽屏插值展开成浅色中文", () => {
  assert.deepEqual([...declarations(wide)].sort(), [
    "Snapshot_wide_portrait_light_zh.default",
    "Snapshot_wide_portrait_light_zh.increase-contrast",
    "Snapshot_wide_portrait_light_zh.reduce-transparency",
  ])
})
