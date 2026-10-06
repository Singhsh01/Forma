import { BlendFunction, Effect, EffectAttribute } from "postprocessing";
import * as THREE from "three";

/**
 * Screen-space architectural line drawing. Edges are found where depth or surface normals change
 * abruptly between neighbouring pixels (a Roberts-cross style operator on the depth buffer and
 * the normal buffer). Only visible surfaces exist in those buffers, so hidden edges are never
 * drawn - unlike a wireframe. Creases inside merged boxes produce no line because coplanar
 * neighbours share depth slope and normal.
 */
const fragment = /* glsl */ `
uniform sampler2D normalBuffer;
uniform vec3 lineColor;
uniform vec3 paperColor;
uniform float thickness;
uniform float depthSensitivity;
uniform float normalSensitivity;
uniform float faceMix;

float lin(float d) {
  float z = getViewZ(d);
  return -z;
}

void mainImage(const in vec4 inputColor, const in vec2 uv, const in float depth, out vec4 outputColor) {
  vec2 px = thickness / resolution;
  float d0 = lin(readDepth(uv));
  float d1 = lin(readDepth(uv + vec2(px.x, px.y)));
  float d2 = lin(readDepth(uv + vec2(-px.x, -px.y)));
  float d3 = lin(readDepth(uv + vec2(px.x, -px.y)));
  float d4 = lin(readDepth(uv + vec2(-px.x, px.y)));
  float dd = abs(d1 - d2) + abs(d3 - d4);
  float depthEdge = smoothstep(0.0, 1.0, dd / max(d0, 1.0) * depthSensitivity);
  vec3 n1 = texture2D(normalBuffer, uv + vec2(px.x, px.y)).xyz * 2.0 - 1.0;
  vec3 n2 = texture2D(normalBuffer, uv + vec2(-px.x, -px.y)).xyz * 2.0 - 1.0;
  vec3 n3 = texture2D(normalBuffer, uv + vec2(px.x, -px.y)).xyz * 2.0 - 1.0;
  vec3 n4 = texture2D(normalBuffer, uv + vec2(-px.x, px.y)).xyz * 2.0 - 1.0;
  float nd = (1.0 - dot(n1, n2)) + (1.0 - dot(n3, n4));
  float normalEdge = smoothstep(0.15, 0.6, nd * normalSensitivity);
  float edge = clamp(max(depthEdge, normalEdge), 0.0, 1.0);
  // fade lines into the far distance like a hand drawing, never on the empty background
  float far = step(0.9999, readDepth(uv));
  edge *= (1.0 - far);
  vec3 base = mix(paperColor, inputColor.rgb, faceMix);
  base = mix(base, paperColor, far);
  outputColor = vec4(mix(base, lineColor, edge), inputColor.a);
}
`;

export class EdgeEffect extends Effect {
  constructor(opts: { normalBuffer: THREE.Texture | null; lineColor: string; paperColor: string; thickness?: number; faceMix?: number }) {
    super("EdgeEffect", fragment, {
      attributes: EffectAttribute.DEPTH,
      blendFunction: BlendFunction.NORMAL,
      uniforms: new Map<string, THREE.Uniform>([
        ["normalBuffer", new THREE.Uniform(opts.normalBuffer)],
        ["lineColor", new THREE.Uniform(new THREE.Color(opts.lineColor))],
        ["paperColor", new THREE.Uniform(new THREE.Color(opts.paperColor))],
        ["thickness", new THREE.Uniform(opts.thickness ?? 1.0)],
        ["depthSensitivity", new THREE.Uniform(9.0)],
        ["normalSensitivity", new THREE.Uniform(1.0)],
        ["faceMix", new THREE.Uniform(opts.faceMix ?? 0.85)],
      ]),
    });
  }

  set normalBuffer(t: THREE.Texture | null) {
    this.uniforms.get("normalBuffer")!.value = t;
  }
}
