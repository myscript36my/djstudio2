package com.djstudio2.app;

import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * GPU-s megjelenítő. A dekóderek közvetlenül két külső (OES) textúrába írnak,
 * a varrás (dual fisheye → gömb) és a nézet vetítése egyetlen fragment shaderben történik,
 * minden képernyőfrissítéskor – a teljes felbontású képkockákból.
 */
final class PanoRenderer implements GLSurfaceView.Renderer {

    interface SurfaceListener {
        void onSurfacesCreated(Surface[] surfaces);
    }

    static final int MODE_EQUIRECT = 0;
    static final int MODE_DUAL = 1;
    static final int MODE_SBS = 2;
    static final int MODE_FLAT = 3;

    // Nézet (bármely szálról állítható)
    volatile int mode = MODE_FLAT;
    volatile int textureCount = 1;
    volatile float videoAspect = 16f / 9f;
    volatile float yawDeg = 0, pitchDeg = 0, fovDeg = 90;

    // Lencse-paraméterek
    volatile float lensFovDeg = 195f;
    volatile float circleScale = 1.0f;
    volatile float blendDeg = 3f;
    volatile float frontRollDeg = 0f, backRollDeg = 0f;
    volatile boolean swapLenses = false;

    private final SurfaceListener listener;
    private final SurfaceTexture[] st = new SurfaceTexture[2];
    private SurfaceTexture[] stale;
    private final int[] tex = new int[2];
    private final float[][] texMat = {new float[16], new float[16]};
    private final AtomicBoolean[] avail = {new AtomicBoolean(), new AtomicBoolean()};
    private final float[] rot = new float[9];
    private final FloatBuffer quad;
    private int program, aPos;
    private int uT0, uT1, uM0, uM1, uRot, uTanHalf, uAspect, uMode, uCount, uVidAspect,
            uHalfFov, uLens, uCircle;
    private int viewW = 1, viewH = 1;

    PanoRenderer(SurfaceListener listener) {
        this.listener = listener;
        float[] v = {-1, -1, 1, -1, -1, 1, 1, 1};
        quad = ByteBuffer.allocateDirect(v.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        quad.put(v).position(0);
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        if (st[0] != null) stale = new SurfaceTexture[]{st[0], st[1]};
        GLES20.glGenTextures(2, tex, 0);
        Surface[] surfaces = new Surface[2];
        for (int i = 0; i < 2; i++) {
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[i]);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            st[i] = new SurfaceTexture(tex[i]);
            final int k = i;
            st[i].setOnFrameAvailableListener(s -> avail[k].set(true));
            Matrix.setIdentityM(texMat[i], 0);
            surfaces[i] = new Surface(st[i]);
        }
        buildProgram();
        listener.onSurfacesCreated(surfaces);
    }

    /** Egy korábbi GL-kontextus felületeit szabadítja fel (a régi lejátszó leállítása után). */
    void releaseStale() {
        if (stale == null) return;
        for (SurfaceTexture s : stale) if (s != null) s.release();
        stale = null;
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        viewW = Math.max(1, width);
        viewH = Math.max(1, height);
        GLES20.glViewport(0, 0, viewW, viewH);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        for (int i = 0; i < 2; i++) {
            if (avail[i].getAndSet(false)) {
                try {
                    st[i].updateTexImage();
                    st[i].getTransformMatrix(texMat[i]);
                } catch (Exception e) {
                    Log.w("PanoRenderer", "updateTexImage", e);
                }
            }
        }

        GLES20.glClearColor(0, 0, 0, 1);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glUseProgram(program);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[0]);
        GLES20.glUniform1i(uT0, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[1]);
        GLES20.glUniform1i(uT1, 1);
        GLES20.glUniformMatrix4fv(uM0, 1, false, texMat[0], 0);
        GLES20.glUniformMatrix4fv(uM1, 1, false, texMat[1], 0);

        computeRotation(yawDeg, pitchDeg);
        GLES20.glUniformMatrix3fv(uRot, 1, false, rot, 0);
        GLES20.glUniform1f(uTanHalf, (float) Math.tan(Math.toRadians(fovDeg) / 2));
        GLES20.glUniform1f(uAspect, (float) viewW / viewH);
        GLES20.glUniform1f(uMode, mode);
        GLES20.glUniform1f(uCount, textureCount);
        GLES20.glUniform1f(uVidAspect, videoAspect);
        GLES20.glUniform1f(uHalfFov, (float) Math.toRadians(lensFovDeg) / 2f);
        GLES20.glUniform4f(uLens,
                (float) Math.toRadians(frontRollDeg),
                (float) Math.toRadians(backRollDeg),
                swapLenses ? 1f : 0f,
                (float) Math.toRadians(Math.max(0.01f, blendDeg)));
        GLES20.glUniform1f(uCircle, circleScale);

        GLES20.glEnableVertexAttribArray(aPos);
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    /** R = Ry(yaw) * Rx(pitch), oszlopfolytonos 3x3. */
    private void computeRotation(float yaw, float pitch) {
        double y = Math.toRadians(yaw), p = Math.toRadians(pitch);
        float c = (float) Math.cos(y), s = (float) Math.sin(y);
        float cb = (float) Math.cos(p), sb = (float) Math.sin(p);
        rot[0] = c;  rot[1] = 0;  rot[2] = -s;
        rot[3] = s * sb; rot[4] = cb; rot[5] = c * sb;
        rot[6] = s * cb; rot[7] = -sb; rot[8] = c * cb;
    }

    private static final String VS =
            "attribute vec2 aPos;\n" +
            "varying vec2 vPos;\n" +
            "void main(){ vPos = aPos; gl_Position = vec4(aPos, 0.0, 1.0); }\n";

    private static final String FS =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision highp float;\n" +
            "uniform samplerExternalOES uT0;\n" +
            "uniform samplerExternalOES uT1;\n" +
            "uniform mat4 uM0;\n" +
            "uniform mat4 uM1;\n" +
            "uniform mat3 uRot;\n" +
            "uniform float uTanHalf, uAspect, uMode, uCount, uVidAspect, uHalfFov, uCircle;\n" +
            "uniform vec4 uLens;\n" +   // x: első forgatás, y: hátsó forgatás, z: csere, w: átmenet
            "varying vec2 vPos;\n" +
            "const float PI = 3.14159265;\n" +
            "vec4 tex0(vec2 uv){ return texture2D(uT0, (uM0 * vec4(uv, 0.0, 1.0)).xy); }\n" +
            "vec4 tex1(vec2 uv){ return texture2D(uT1, (uM1 * vec4(uv, 0.0, 1.0)).xy); }\n" +
            "vec2 rot2(vec2 p, float a){ float c = cos(a), s = sin(a); return vec2(c*p.x - s*p.y, s*p.x + c*p.y); }\n" +
            // Ekvidisztáns fisheye: a lencse a -Z felé néz, a képkör kitölti a négyzetet.
            "vec2 fishUV(vec3 d, float roll){\n" +
            "  float th = acos(clamp(-d.z, -1.0, 1.0));\n" +
            "  float l = length(d.xy);\n" +
            "  vec2 n = l > 1e-6 ? d.xy / l : vec2(0.0);\n" +
            "  vec2 p = n * (th / uHalfFov) * 0.5 * uCircle;\n" +
            "  return rot2(p, roll) + 0.5;\n" +
            "}\n" +
            "void main(){\n" +
            "  if (uMode > 2.5) {\n" +                           // lapos / nyers nézet
            "    vec2 q = vPos * 0.5;\n" +
            "    float A = uVidAspect * (uCount > 1.5 ? 2.0 : 1.0);\n" +
            "    if (uAspect > A) q.x *= uAspect / A; else q.y *= A / uAspect;\n" +
            "    q += 0.5;\n" +
            "    if (q.x < 0.0 || q.x > 1.0 || q.y < 0.0 || q.y > 1.0) { gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0); return; }\n" +
            "    if (uCount > 1.5) gl_FragColor = q.x < 0.5 ? tex0(vec2(q.x * 2.0, q.y)) : tex1(vec2(q.x * 2.0 - 1.0, q.y));\n" +
            "    else gl_FragColor = tex0(q);\n" +
            "    return;\n" +
            "  }\n" +
            "  vec3 d = normalize(uRot * vec3(vPos.x * uTanHalf * uAspect, vPos.y * uTanHalf, -1.0));\n" +
            "  if (uMode < 0.5) {\n" +                           // ekvirektanguláris 360
            "    float lon = atan(d.x, -d.z);\n" +
            "    float lat = asin(clamp(d.y, -1.0, 1.0));\n" +
            "    gl_FragColor = tex0(vec2(lon / (2.0 * PI) + 0.5, lat / PI + 0.5));\n" +
            "    return;\n" +
            "  }\n" +
            "  vec3 dB = vec3(-d.x, d.y, -d.z);\n" +              // hátsó lencse: 180°-kal elforgatva
            "  float thF = acos(clamp(-d.z, -1.0, 1.0));\n" +
            "  float w = smoothstep(PI * 0.5 - uLens.w, PI * 0.5 + uLens.w, thF);\n" +
            "  vec2 uvF = fishUV(d, uLens.x);\n" +
            "  vec2 uvB = fishUV(dB, uLens.y);\n" +
            "  vec4 cF; vec4 cB;\n" +
            "  if (uMode < 1.5) {\n" +                           // két külön sáv (OSV)
            "    if (uLens.z > 0.5) { cF = tex1(uvF); cB = tex0(uvB); } else { cF = tex0(uvF); cB = tex1(uvB); }\n" +
            "  } else {\n" +                                     // egy képen egymás mellett
            "    float oF = uLens.z > 0.5 ? 0.5 : 0.0;\n" +
            "    vec2 a = vec2(uvF.x * 0.5 + oF, uvF.y);\n" +
            "    vec2 b = vec2(uvB.x * 0.5 + 0.5 - oF, uvB.y);\n" +
            "    cF = tex0(a); cB = tex0(b);\n" +
            "  }\n" +
            "  gl_FragColor = mix(cF, cB, w);\n" +
            "}\n";

    private void buildProgram() {
        int vs = compile(GLES20.GL_VERTEX_SHADER, VS);
        int fs = compile(GLES20.GL_FRAGMENT_SHADER, FS);
        program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vs);
        GLES20.glAttachShader(program, fs);
        GLES20.glLinkProgram(program);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) Log.e("PanoRenderer", "link: " + GLES20.glGetProgramInfoLog(program));
        aPos = GLES20.glGetAttribLocation(program, "aPos");
        uT0 = GLES20.glGetUniformLocation(program, "uT0");
        uT1 = GLES20.glGetUniformLocation(program, "uT1");
        uM0 = GLES20.glGetUniformLocation(program, "uM0");
        uM1 = GLES20.glGetUniformLocation(program, "uM1");
        uRot = GLES20.glGetUniformLocation(program, "uRot");
        uTanHalf = GLES20.glGetUniformLocation(program, "uTanHalf");
        uAspect = GLES20.glGetUniformLocation(program, "uAspect");
        uMode = GLES20.glGetUniformLocation(program, "uMode");
        uCount = GLES20.glGetUniformLocation(program, "uCount");
        uVidAspect = GLES20.glGetUniformLocation(program, "uVidAspect");
        uHalfFov = GLES20.glGetUniformLocation(program, "uHalfFov");
        uLens = GLES20.glGetUniformLocation(program, "uLens");
        uCircle = GLES20.glGetUniformLocation(program, "uCircle");
    }

    private static int compile(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) Log.e("PanoRenderer", "shader: " + GLES20.glGetShaderInfoLog(s));
        return s;
    }
}
