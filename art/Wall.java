import java.awt.*; import java.awt.image.*; import javax.imageio.*; import javax.imageio.stream.*; import java.io.*;
/** Scales a wallpaper to 1152 px wide and writes it as a quality-92 JPEG:  java Wall.java in out.jpg */
public class Wall { public static void main(String[] a) throws Exception {
  BufferedImage s = ImageIO.read(new File(a[0]));
  int w = 1152, h = (int) Math.round((double) s.getHeight() * w / s.getWidth());
  BufferedImage cur = s;
  while (cur.getWidth() / 2 >= w) cur = resize(cur, cur.getWidth() / 2, cur.getHeight() / 2);
  BufferedImage o = resize(cur, w, h);
  ImageWriter wr = ImageIO.getImageWritersByFormatName("jpg").next();
  ImageWriteParam p = wr.getDefaultWriteParam(); p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT); p.setCompressionQuality(0.92f);
  try (ImageOutputStream os = ImageIO.createImageOutputStream(new File(a[1]))) { wr.setOutput(os); wr.write(null, new IIOImage(o, null, null), p); }
  System.out.println(w + "x" + h);
}
static BufferedImage resize(BufferedImage im, int w, int h) {
  BufferedImage o = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB); Graphics2D g = o.createGraphics();
  g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
  g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
  g.drawImage(im, 0, 0, w, h, null); g.dispose(); return o; }
}
