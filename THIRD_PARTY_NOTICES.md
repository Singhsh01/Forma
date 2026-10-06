# Third-party notices

## Ideas and algorithms

* **MarkovJunior** by Maxim Gumin (MIT License, https://github.com/mxgmn/MarkovJunior). FORMA's rule engine follows MarkovJunior's model of rewrite rules on a grid executed by `one`, `all` (forall) and `prl` (parallel) nodes inside sequence and Markov nodes, with rule symmetries. FORMA's implementation, rule text syntax, cell alphabet and everything around it (architectural presets, validation, replay) are written from scratch in Java; no MarkovJunior source code is included.
* **Wave Function Collapse** by Maxim Gumin (MIT License, https://github.com/mxgmn/WaveFunctionCollapse). FORMA implements the simple tiled model idea (lowest-entropy observation, arc-consistent propagation) independently, with explicit sockets and bounded restarts.
* **ConvChain** by Maxim Gumin (MIT License, https://github.com/mxgmn/ConvChain) was studied as a reference for MCMC in procedural generation. FORMA does not use or include it: its refinement is Metropolis-Hastings over architectural massings.
* **Metropolis-Hastings** and **simulated annealing** are standard published algorithms.

## Bundled with the build

| Component | Version | License |
|---|---|---|
| Apache Maven Wrapper scripts (`mvnw`, `mvnw.cmd`, `.mvn/wrapper`) | 3.3.4 | Apache-2.0 |
| Syne variable font (via @fontsource-variable/syne) | 5.3.0 | SIL Open Font License 1.1 |
| Instrument Sans variable font (via @fontsource-variable/instrument-sans) | 5.3.0 | SIL Open Font License 1.1 |

## Web runtime dependencies (npm)

| Package | Version | License |
|---|---|---|
| react, react-dom | 19.2.3 | MIT |
| three | 0.186.1 | MIT |
| @react-three/fiber | 9.8.1 | MIT |
| @react-three/drei | 10.7.9 | MIT |
| @react-three/postprocessing | 3.1.3 | MIT |
| postprocessing | 6.39.5 | Zlib |
| motion | 12.43.0 | MIT |
| @phosphor-icons/react | 2.1.10 | MIT |

## Development and test tools (not shipped in the app)

| Package | Version | License |
|---|---|---|
| vite | 7.3.6 | MIT |
| @vitejs/plugin-react | 5.2.0 | MIT |
| typescript | 5.9.3 | Apache-2.0 |
| vitest | 4.1.11 | MIT |
| @playwright/test | 1.56.1 | Apache-2.0 |
| @types/* | various | MIT |
| JUnit 5 (Jupiter, Platform) | 5.12.2 (Maven) | EPL-2.0 |

The Java engine and server have no third-party runtime dependencies; they use only the JDK.
