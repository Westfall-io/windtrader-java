# [0.2.0](https://github.com/Westfall-io/windtrader-java/compare/v0.1.4...v0.2.0) (2026-10-08)


### Features

* Add SysMLv2 element JSON export mode (bundled stdlib, batch-export, golden tests) ([284d9f9](https://github.com/Westfall-io/windtrader-java/commit/284d9f9ca3d6ac6d055d2d5d01cd9e0d9f5924c8)), closes [#8](https://github.com/Westfall-io/windtrader-java/issues/8)


### Bug Fixes

* Address independent review (REQUEST_CHANGES) — fail loudly on transform error, hoist library load, reproducible golden ([5aa5060](https://github.com/Westfall-io/windtrader-java/commit/5aa50600a0faa6941dfb5bd18d2292a6c208343d)), closes [#9](https://github.com/Westfall-io/windtrader-java/issues/9) [#2](https://github.com/Westfall-io/windtrader-java/issues/2) [#4](https://github.com/Westfall-io/windtrader-java/issues/4) [#7](https://github.com/Westfall-io/windtrader-java/issues/7) [#8](https://github.com/Westfall-io/windtrader-java/issues/8) [#10](https://github.com/Westfall-io/windtrader-java/issues/10) [#11](https://github.com/Westfall-io/windtrader-java/issues/11)
* Fix export silently exiting 0 on failure + add invalid-export smoke test ([1cb0539](https://github.com/Westfall-io/windtrader-java/commit/1cb0539028bc32095e1cd917c3715d8d5218a7c1))
* Review fixes: fail loudly without stdlib, remove double-parse, tighten export exit contract ([5bc8812](https://github.com/Westfall-io/windtrader-java/commit/5bc8812b128daa07e37bf66740c3dd75ac00aa5d))


### Maintenance

* Post-review polish: byte-reproducible golden, error-level rises, cross-path check hardening ([1db7f94](https://github.com/Westfall-io/windtrader-java/commit/1db7f9462ffa919b64c11178813b1ace41728ece)), closes [#9](https://github.com/Westfall-io/windtrader-java/issues/9)

## [0.1.4](https://github.com/Westfall-io/windtrader-java/compare/v0.1.3...v0.1.4) (2026-09-29)


### Bug Fixes

* Address review: assert 96-file corpus count, add round-trip echo leg, excluded-class smoke test, README pin update ([d9e278e](https://github.com/Westfall-io/windtrader-java/commit/d9e278e697a98bcbd61e339e5f7088f474fd3e58))
* Review respin: non-empty echo guard + idempotence leg, wildcard exclusion with remove-condition ([0451c63](https://github.com/Westfall-io/windtrader-java/commit/0451c63cfc490442fbb56e3d6bdd6c45fad47bdd))

## [0.1.3](https://github.com/Westfall-io/windtrader-java/compare/v0.1.2...v0.1.3) (2026-09-28)


### Bug Fixes

* Address code review: anchored corpus skips, fast units regression test, SysMLStandaloneSetup, README pin ([80cff0d](https://github.com/Westfall-io/windtrader-java/commit/80cff0dc8d81814c0d76e03c5d4f1850f1c121ad))
* Fix unit-expression NPE: bootstrap SysMLInteractive for full EMF/EPackage wiring ([7426996](https://github.com/Westfall-io/windtrader-java/commit/74269961a4cd7c59f4ae3a72b8fdd589b309a2e7))
* Fix unit-expression NPE: bootstrap SysMLInteractive for full EMF/EPackage wiring (#5) ([6e716fb](https://github.com/Westfall-io/windtrader-java/commit/6e716fbccd41f86da2d4ba97e03bf694d76a61a6)), closes [#5](https://github.com/Westfall-io/windtrader-java/issues/5)

## [0.1.2](https://github.com/Westfall-io/windtrader-java/compare/v0.1.1...v0.1.2) (2026-09-11)


### Documentation

* Relicense to EPL-2.0 and bump SysML pilot pin to 0.60.0 ([137dc56](https://github.com/Westfall-io/windtrader-java/commit/137dc5637a88c97996a2a20774772fff628d2cb3)), closes [#1](https://github.com/Westfall-io/windtrader-java/issues/1) [#3](https://github.com/Westfall-io/windtrader-java/issues/3)

## [0.1.1](https://github.com/Westfall-io/windtrader-java/compare/v0.1.0...v0.1.1) (2026-01-17)


### Bug Fixes

* trigger release ([e69bc85](https://github.com/Westfall-io/windtrader-java/commit/e69bc856bd95dd2456a03b7c22dd6798eedd6dd3))
* trigger release ([68d14d0](https://github.com/Westfall-io/windtrader-java/commit/68d14d0014e9b0bd771270e85053597aed67e2db))
* trigger release ([19ba7f8](https://github.com/Westfall-io/windtrader-java/commit/19ba7f88fad797a9bd5740712251f1a940841fc8))
* trigger release ([6cdc96f](https://github.com/Westfall-io/windtrader-java/commit/6cdc96f8090cfa2dd01b93d78741ddbf2e50d66f))
* trigger release ([bce944f](https://github.com/Westfall-io/windtrader-java/commit/bce944ffd77536caaf7b14db3881f6de0f57f04e))
* trigger release ([5142f22](https://github.com/Westfall-io/windtrader-java/commit/5142f22969f2488e608cd5072c191618c90e4e8c))
* trigger release ([2597cd5](https://github.com/Westfall-io/windtrader-java/commit/2597cd55f7d247b653ab69263840095ed2bff517))
* trigger release ([785703c](https://github.com/Westfall-io/windtrader-java/commit/785703cd564c7bcc2abb2a448c8d7af34c3fe6ff))


### Maintenance

* Track release.config.js ([43c5f99](https://github.com/Westfall-io/windtrader-java/commit/43c5f9927e7ee0cd00a49a8618e8be941e49f8c9))

## [0.1.1](https://github.com/Westfall-io/windtrader-java/compare/v0.1.0...v0.1.1) (2026-01-17)


### Bug Fixes

* trigger release ([6cdc96f](https://github.com/Westfall-io/windtrader-java/commit/6cdc96f8090cfa2dd01b93d78741ddbf2e50d66f))
* trigger release ([bce944f](https://github.com/Westfall-io/windtrader-java/commit/bce944ffd77536caaf7b14db3881f6de0f57f04e))
* trigger release ([5142f22](https://github.com/Westfall-io/windtrader-java/commit/5142f22969f2488e608cd5072c191618c90e4e8c))
* trigger release ([2597cd5](https://github.com/Westfall-io/windtrader-java/commit/2597cd55f7d247b653ab69263840095ed2bff517))
* trigger release ([785703c](https://github.com/Westfall-io/windtrader-java/commit/785703cd564c7bcc2abb2a448c8d7af34c3fe6ff))
