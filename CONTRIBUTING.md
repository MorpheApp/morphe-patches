# Contributing to Morphe Patches

We currently accept pull requests for fixes and improvements to YouTube, YouTube Music and Reddit.
If you have developed patches for other apps, you are encouraged to release them in a third party
repository. See the [Patches template](https://github.com/morpheapp/morphe-patches-template) for
how to get started.

If you are using an agent to review or refactor your code, please provide this file to your agent
for reviews.

## Patch guidelines

### Disabled patches do nothing

When all of a patch's settings are off, the patch must behave as if it was not included at all:
no UI changes, no background work (databases, threads, network) and no extra work in hooks that run
often. Hooks check the setting first and "do nothing" if the setting is off.

### App versions

- If a patch cannot work on older versions, check the version in the patch's `execute`, log a
  warning with `Logger.getLogger(this::class.java.name).warning(...)` and return. State the
  required version in the patch description ("This patch works with Reddit X and newer.").

### Finding code to patch

- Fingerprints, and patch code in general, should never use obfuscated class/method/field names.
- Use `Fingerprint` and `InstructionFilter` for method searching. Avoid searching by hand, such as
  `classDefForEach` or searching method instructions for indexes.
- Avoid Fingerprint `custom = { }` blocks unless absolutely necessary. If one is required, use only
  simple constant checks and do not iterate method instructions. To find a 'sibling' method in a
  class, use a `classFingerprint` declaration with its own fingerprint.
- Except with `matchAll()`, a Fingerprint must match exactly one method. Verify this with
  `--validate` (see [Testing](#testing)), which fails if a fingerprint is not precise enough. Only
  use `matchSingle()` if an app has no version constraint and the fingerprint may not match
  precisely in future app versions.
- Assume fingerprints always match and do not use `matchOrNull()` or other fail-silent checks.
  If code really does not exist in some app versions, gate it with `versionCheckPatch`.
- If the code differs significantly between app versions, write a separate legacy fingerprint and
  choose it by version, rather than combining both shapes with `anyInstruction`.
- Prefer `string(...)` filters whenever the method has a usable string. Strings are usually very
  specific and identify code uniquely. Otherwise, use stable anchors (other literals,
  known API calls, distinctive instructions) over things that change between versions, such as
  call opcodes and access flags.
- Use the built-in filters (`methodCall()`, `fieldAccess()`, `opcode()`) instead of custom
  `InstructionFilter { }` lambdas. Express "instruction X right before Y" with filter locations,
  not manual index checks.
- With `MatchAfterWithin(n)` / `MatchBeforeWithin(n)`, use a slightly larger range than the current
  app needs (usually 1-5 extra), so future versions that add register moves or other instructions
  still match.
- If using shared fingerprints, and other patches make changes using that shared fingerprint,
  then in general the patch should call `clearMatch()` before using the fingerprint so instruction
  match indexes are up to date.

### Writing smali hooks

- To modify a method called by a matched instruction, use `instructionMatches[i].getMethodCalled()`
  (and `getFieldAccessed()` for fields), not `mutableClassDefBy(...).findMutableMethodOf(...)`.
- Use existing Patcher and project helper methods instead of hand-rolled register math:
  `p0Register` for the first parameter register, `registersUsed[N]` for the Nth register of an
  invoke.
- If a method has no free registers for `FreeRegisterProvider`, then use `cloneParameters()`
  and use the parameter registers as free registers.
- Prefer an inline label with `nop` at the end of the inserted smali over
  `ExternalLabel(..., getInstruction(i))`. Use `ExternalLabel` only when the target is an existing
  instruction elsewhere. Inline labels require `addInstructionsWithLabels`.

### Accessing app code from extensions

- Prefer adding interfaces and helper methods to obfuscated app classes over reflection. Methods
  added to app classes are named `patch_...`.
- For an example, see the interfaces declared in the YouTube extension's `VideoInformation` class,
  which `VideoInformationPatch` adds to the app classes together with their `patch_...` methods.

### Reuse

- Reuse existing shared code instead of writing parallel versions.
- Do not force sharing when it needs workaround code. Similar-looking code with different purposes
  can stay separate.
- Name code that is specific to one app accordingly.

## Extension code style

- Keep it simple: no unnecessary threads, workarounds or defensive code. Add a brief comment only
  where something unusual is needed.
- Comments are plain ASCII unless quoting Unicode literal characters/strings.
- Never swallow exceptions. Either let them propagate or log them. Injection points (methods called
  from patch code) wrap their body in try/catch and log with
  `Logger.printException(() -> "methodName failure", ex);`. Very simple injection points (such as
  only returning a setting value) do not need a try/catch.
- Use `@Nullable` where a parameter, field or return value can be null. Do not use `@NonNull`
  unless overriding a method or implementing an interface that already has it.
- Code must work on the minimum supported Android SDK for the target app.
- Use `final` modifier for local primitive values and fields when possible. In general, do not
  use `final` for local object variables or local array declarations.
- Use named constants instead of magic numbers.

## Strings and translations

Translations are managed on [Crowdin](https://morphe.software/translate). Only add, change or
remove strings in the English `values/` resources. Do not edit the translated `values-*` files:
Crowdin syncs those automatically.

Use `%s` for format placeholders unless the value needs a format feature such as decimal places
(`%.1f`) or padding. For example, use `%s` and not `%d` for a plain integer. `%s` accepts any
argument, while a type letter like `%d` throws an exception if a translation changes it or the code
later passes a different type. Use a plain `%s` when a string has only one argument, and indexed
placeholders (`%1$s`, `%2$s`) only when it has more than one, so translations can reorder them.

## Testing

- Test on the minimum and maximum recommended app versions, and on the latest experimental versions
  if possible.
- Patch the latest non-experimental and the latest experimental app targets with `--validate`
  using Morphe Desktop CLI. Validation must pass with no errors and no fingerprints that match more
  than one method. Other app targets do not need to pass validation.
- 'Slow' fingerprints can be fixed if possible, but it is not a requirement. They can be improved by
  adding more specificity, especially more `InstructionFilter`s such as `string()`, `literal()` or
  any filters with full object types.
- When finding the minimum version a patch supports, test with the version gate removed, using the
  Desktop CLI `-f` argument. A gated patch reports success on versions where it silently does
  nothing.

## Code origin

In general, we only accept contributions that you created yourself or that you hold the copyright for.

Output of the tools you work with (code generators, assistants, your editor, and so on) is
considered your own work, and you are responsible for it like everything else submitted.

## License

By contributing, you agree that your contributions are licensed under the
[GNU General Public License v3.0](LICENSE), with the additional conditions in [NOTICE](NOTICE).
