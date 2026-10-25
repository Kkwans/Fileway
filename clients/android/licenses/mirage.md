# Mirage / Paper Design shaders

The player atmosphere uses the unmodified `MeshGradient` composable from
`io.github.androidpoet:mirage:0.1.0`, published on Maven Central. It translates
Paper Design's flowing mesh gradient algorithm to Android AGSL.

- Mirage: https://github.com/AndroidPoet/mirage
- Reviewed source revision: `9da9b00f678d57ce9989838ac513ffa88a11b038`.
- Published sources SHA-256: `b217338254ac9c4422bb579ddb5dde280e9c73da2b7b7f96d3f2c0e832bc5288`.
- Published AAR SHA-256: `7ee9b6d678349acb611dcd559ca230cf0e2e6e0355f953a70ba8226f903ecd2b`.
- Upstream algorithms: https://github.com/paper-design/shaders
- Apache 2.0; see `mirage-Apache-2.0.txt` and `mirage-NOTICE.txt`.

Fileway supplies artwork-derived colors, lifecycle/motion gating and a static
fallback below Android 13. The native video surface is neither sampled nor filtered.
