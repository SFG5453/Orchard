/*
 * Copyright (C) 2026 SFG545
 *
 * This file is part of Orchard.
 *
 * Orchard is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * Orchard is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

const AMBIENT_WIDTH_RATIO = 0.78;
const AMBIENT_HEIGHT_RATIO = 1.5;

export function albumAmbientSpriteLayout(hostWidth, hostHeight, sourceWidth, sourceHeight) {
  const width = Math.max(1, Number(hostWidth) || 0);
  const height = Math.max(1, Number(hostHeight) || 0);
  const textureWidth = Math.max(1, Number(sourceWidth) || 0);
  const textureHeight = Math.max(1, Number(sourceHeight) || 0);
  const scale = Math.max(
    (width * AMBIENT_WIDTH_RATIO) / textureWidth,
    (height * AMBIENT_HEIGHT_RATIO) / textureHeight
  );
  return {
    scale,
    x: width * 0.33,
    y: height * 0.5
  };
}

export function albumSeamSpriteLayout(hostRect, videoRect, sourceWidth, sourceHeight) {
  const hostLeft = Number(hostRect?.left) || 0;
  const hostTop = Number(hostRect?.top) || 0;
  const videoLeft = Number(videoRect?.left) || 0;
  const videoTop = Number(videoRect?.top) || 0;
  const videoWidth = Math.max(1, Number(videoRect?.width) || 0);
  const videoHeight = Math.max(1, Number(videoRect?.height) || 0);
  const textureWidth = Math.max(1, Number(sourceWidth) || 0);
  const textureHeight = Math.max(1, Number(sourceHeight) || 0);
  return {
    scaleX: videoWidth / textureWidth,
    scaleY: videoHeight / textureHeight,
    x: (videoLeft - hostLeft) + (videoWidth * 0.5),
    y: (videoTop - hostTop) + (videoHeight * 0.5)
  };
}

const vertex = `
  attribute vec2 uv;
  attribute vec3 position;
  uniform mat4 modelViewMatrix;
  uniform mat4 projectionMatrix;
  varying vec2 vUv;
  void main() {
    vUv = uv;
    gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0);
  }
`;

const fragment = `
  precision mediump float;
  uniform sampler2D tMap;
  uniform float uAlpha;
  uniform float uBlurRadius;
  uniform vec2 uResolution;
  varying vec2 vUv;

  void main() {
    vec4 color = vec4(0.0);
    float total = 0.0;
    vec2 texelSize = 1.0 / uResolution;
    
    // Fast 25-tap blur
    for(float x = -2.0; x <= 2.0; x += 1.0) {
      for(float y = -2.0; y <= 2.0; y += 1.0) {
        vec2 offset = vec2(x, y) * (uBlurRadius / 2.0) * texelSize;
        color += texture2D(tMap, vUv + offset);
        total += 1.0;
      }
    }
    
    gl_FragColor = (color / total) * uAlpha;
  }
`;

export async function createAlbumVideoAmbientRenderer({ host, video }) {
  if (!host || !video || video.videoWidth < 1 || video.videoHeight < 1) {
    throw new Error('Album video ambient rendering requires a ready video and host.');
  }

  const { Renderer, Camera, Transform, Texture, Program, Mesh, Plane } = await import('ogl');

  const initialWidth = Math.max(1, host.clientWidth);
  const initialHeight = Math.max(1, host.clientHeight);
  
  const renderer = new Renderer({
    width: initialWidth,
    height: initialHeight,
    dpr: 0.75,
    alpha: true,
    premultipliedAlpha: false,
    antialias: false,
    powerPreference: 'low-power'
  });
  const gl = renderer.gl;

  // Set up orthographic camera matching DOM coordinates (origin top-left, y goes down)
  const camera = new Camera(gl, {
    left: 0,
    right: initialWidth,
    top: 0,
    bottom: initialHeight,
    near: 0.1,
    far: 100
  });
  camera.position.z = 1;

  const scene = new Transform();

  const texture = new Texture(gl, {
    image: video,
    generateMipmaps: false,
    minFilter: gl.LINEAR,
    magFilter: gl.LINEAR
  });

  const geometry = new Plane(gl);

  const programAmbient = new Program(gl, {
    vertex,
    fragment,
    uniforms: {
      tMap: { value: texture },
      uAlpha: { value: 0.76 },
      uBlurRadius: { value: 46.0 },
      uResolution: { value: [video.videoWidth, video.videoHeight] }
    },
    transparent: true
  });

  const programSeam = new Program(gl, {
    vertex,
    fragment,
    uniforms: {
      tMap: { value: texture },
      uAlpha: { value: 1.0 },
      uBlurRadius: { value: 20.0 },
      uResolution: { value: [video.videoWidth, video.videoHeight] }
    },
    transparent: true
  });

  const ambientMesh = new Mesh(gl, { geometry, program: programAmbient });
  const seamMesh = new Mesh(gl, { geometry, program: programSeam });
  
  ambientMesh.setParent(scene);
  seamMesh.setParent(scene);

  renderer.gl.canvas.className = 'album-hero-video-ambient__canvas';
  renderer.gl.canvas.setAttribute('aria-hidden', 'true');
  host.replaceChildren(renderer.gl.canvas);

  let destroyed = false;
  let animationFrameId;

  function layout() {
    if (destroyed) return;
    const width = Math.max(1, host.clientWidth);
    const height = Math.max(1, host.clientHeight);
    renderer.setSize(width, height);
    
    camera.orthographic({ left: 0, right: width, top: 0, bottom: height, near: 0.1, far: 100 });

    const ambient = albumAmbientSpriteLayout(
      width,
      height,
      video.videoWidth,
      video.videoHeight
    );
    ambientMesh.scale.set(video.videoWidth * ambient.scale, video.videoHeight * ambient.scale, 1);
    ambientMesh.position.set(ambient.x, ambient.y, 0);

    const seam = albumSeamSpriteLayout(
      host.getBoundingClientRect(),
      video.getBoundingClientRect(),
      video.videoWidth,
      video.videoHeight
    );
    seamMesh.scale.set(video.videoWidth * seam.scaleX, video.videoHeight * seam.scaleY, 1);
    seamMesh.position.set(seam.x, seam.y, 0);
  }

  function renderVideoFrame() {
    if (destroyed) return;
    if (video.readyState >= video.HAVE_CURRENT_DATA) {
      texture.needsUpdate = true;
    }
    renderer.render({ scene, camera });
    animationFrameId = requestAnimationFrame(renderVideoFrame);
  }

  layout();
  renderVideoFrame();

  const resizeObserver = new ResizeObserver(layout);
  resizeObserver.observe(host);
  resizeObserver.observe(video);
  host.classList.add('is-ready');

  return {
    destroy() {
      if (destroyed) return;
      destroyed = true;
      host.classList.remove('is-ready');
      resizeObserver.disconnect();
      cancelAnimationFrame(animationFrameId);
      
      ambientMesh.setParent(null);
      seamMesh.setParent(null);
      geometry.remove();
      programAmbient.remove();
      programSeam.remove();
      // OGL textures don't have a direct destroy method, deleting the WebGL texture works:
      if (texture.texture) {
        gl.deleteTexture(texture.texture);
      }
      
      host.replaceChildren();
    }
  };
}
