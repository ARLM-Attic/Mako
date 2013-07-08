import javax.sound.sampled.*;
import java.util.Random;
import java.io.*;

public class MakoVM implements MakoConstants {

	private final Random rand = new Random();
	public final int[] m;                      // main memory
	public final int[] p = new int[320 * 240]; // pixel buffer
	public int keys = 0;

	private SourceDataLine soundLine = null;
	private final byte[] abuffer = new byte[8000];
	private int apointer = 0;

	public final java.util.Queue<Integer> keyQueue = new java.util.LinkedList<Integer>();

	public int xc = 1;
	public final java.util.Map<Integer, PushbackInputStream> xi  = new java.util.HashMap<Integer, PushbackInputStream>();
	public final java.util.Map<Integer, OutputStream> xo = new java.util.HashMap<Integer, OutputStream>();

	public MakoVM(int[] m) {
		this.m = m;
		try {
			AudioFormat format = new AudioFormat(8000f, 8, 1, false, false);
			DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
			soundLine = (SourceDataLine)AudioSystem.getLine(info);
			soundLine.open(format, 670);
			soundLine.start();
		}
		catch(IllegalArgumentException e) { System.out.println("Unable to initialize sound."); }
		catch(LineUnavailableException e) { e.printStackTrace(); }
	}

	private void push(int v)      { m[m[DP]++] = v; }
	private void rpush(int v)     { m[m[RP]++] = v; }
	private int pop()             { return m[--m[DP]]; }
	private int rpop()            { return m[--m[RP]]; }
	private int mod(int a, int b) { a %= b; return a < 0 ? a+b : a; }

	public void run() {
		while(m[PC] != -1 && m[m[PC]] != OP_SYNC) {
			tick();
		}
		if (m[PC] == -1) { System.exit(0); }
		m[PC]++;
		sync();
	}

	public void tick() {
		int o = m[m[PC]++];
		int a, b;

		switch(o) {
			case OP_CONST  :  push(m[m[PC]++]);                       break;
			case OP_CALL   : rpush(m[PC]+1); m[PC] = m[m[PC]];        break;
			case OP_JUMP   :                 m[PC] = m[m[PC]];        break;
			case OP_JUMPZ  : m[PC] = pop()==0 ? m[m[PC]] : m[PC]+1;   break;
			case OP_JUMPIF : m[PC] = pop()!=0 ? m[m[PC]] : m[PC]+1;   break;
			case OP_LOAD   : push(load(pop()));                       break;
			case OP_STOR   : stor(pop(),pop());                       break;
			case OP_RETURN : m[PC] = rpop();                          break;
			case OP_DROP   : pop();                                   break;
			case OP_SWAP   : a = pop(); b = pop(); push(a); push(b);  break;
			case OP_DUP    : push(m[m[DP]-1]);                        break;
			case OP_OVER   : push(m[m[DP]-2]);                        break;
			case OP_STR    : rpush(pop());                            break;
			case OP_RTS    : push(rpop());                            break;
			case OP_ADD    : a = pop(); b = pop(); push(b+a);         break;
			case OP_SUB    : a = pop(); b = pop(); push(b-a);         break;
			case OP_MUL    : a = pop(); b = pop(); push(b*a);         break;
			case OP_DIV    : a = pop(); b = pop(); push(b/a);         break;
			case OP_MOD    : a = pop(); b = pop(); push(mod(b,a));    break;
			case OP_AND    : a = pop(); b = pop(); push(b&a);         break;
			case OP_OR     : a = pop(); b = pop(); push(b|a);         break;
			case OP_XOR    : a = pop(); b = pop(); push(b^a);         break;
			case OP_NOT    : push(~pop());                            break;
			case OP_SGT    : a = pop(); b = pop(); push(b>a ? -1:0);  break;
			case OP_SLT    : a = pop(); b = pop(); push(b<a ? -1:0);  break;
			case OP_NEXT   : m[PC] = --m[m[RP]-1]<0?m[PC]+1:m[m[PC]]; break;
		}
	}

	private int normalizeRead(PushbackInputStream str) throws IOException {
		int ret = str.read();
		if(ret == '\r') {
			int tmp = str.read();
			if(tmp != '\n') {
				str.unread(tmp);
			}
			ret = '\n';
		}
		return ret;
	}

	private int load(int addr) {
		if (addr == RN) { return rand.nextInt(); }
		if (addr == KY) { return keys; }
		if (addr == KB) {
			if (keyQueue.size() > 0) { return keyQueue.remove(); }
			return -1;
		}
		if (addr == CO) {
			try { return System.in.read(); }
			catch(IOException e) { e.printStackTrace(); }
		}
		if (addr == XO) {
			if (xi.containsKey(m[XA])) {
				try { return normalizeRead(xi.get(m[XA])); }
				catch(IOException e) { e.printStackTrace(); }
			}
			return -1;
		}
		if (addr == XS) {
			return 3; // supports reading/writing local files
		}
		return m[addr];
	}

	private String extractString(int addr) {
		StringBuilder ret = new StringBuilder();
		while(addr >= 0 && addr < m.length && m[addr] != 0) {
			ret.append((char)(m[addr++]));
		}
		return ret.toString();
	}

	private void stor(int addr, int value) {
		if (addr == CO) { System.out.print((char)value); return; }
		if (addr == AU) {
			abuffer[apointer] = (byte)value;
			if (apointer < abuffer.length - 1) { apointer++; }
		}
		if (addr == XO) {
			if (xo.containsKey(m[XA])) {
				try { xo.get(m[XA]).write(value); }
				catch(IOException e) { e.printStackTrace(); }
			}
		}
		if (addr == XS) {
			try {
				if (value == X_CLOSE) {
					if (xi.containsKey(m[XA])) { xi.get(m[XA]).close(); }
					if (xo.containsKey(m[XA])) { xo.get(m[XA]).close(); }
					xi.remove(m[XA]);
					xo.remove(m[XA]);
				}
				if (value == X_OPEN_READ) {
					xi.put(xc, new PushbackInputStream(new FileInputStream(extractString(m[XA]))));
					m[XA] = xc++;
				}
				if (value == X_OPEN_WRITE) {
					xo.put(xc, new FileOutputStream(extractString(m[XA])));
					m[XA] = xc++;
				}
			}
			catch(IOException e) { m[XA] = -1; }
		}
		m[addr] = value;
	}

	private void drawPixel(int x, int y, int c) {
		if ((c & 0xFF000000) != 0xFF000000)         { return; }
		if (x < 0 || x >= 320 || y < 0 || y >= 240) { return; }
		p[x + (y * 320)] = c;
	}

	private void drawTile(int scanline, int x_offset, int tile_id, int tile_line) {
		int i = m[GT] + (tile_id * 8 * 8) + (tile_line * 8);
		for(int x = 0; x < 8; x++) {
			drawPixel(x_offset + x, scanline, m[i++]);
		}
	}

	/*
	private void drawSprite(int tile, int status, int px, int py) {
		// skip it if it's not within this raster?

		if (status % 2 == 0) { return; }
		final int w = (((status & 0x0F00) >>  8) + 1) * 8;
		final int h = (((status & 0xF000) >> 12) + 1) * 8;
		int xd = 1; int x0 = 0; int x1 = w;
		int yd = 1; int y0 = 0; int y1 = h;
		if ((status & H_MIRROR_MASK) != 0) { xd = -1; x0 = w - 1; x1 = -1; }
		if ((status & V_MIRROR_MASK) != 0) { yd = -1; y0 = h - 1; y1 = -1; }
		int i = m[ST] + (tile * w * h);
		for(int y = y0; y != y1; y += yd) {
			for(int x = x0; x != x1; x += xd) {
				drawPixel(x+px, y+py, m[i++]);
			}
		}
	}
	*/

	private void drawGrid(boolean hiz, int y) {
		final int grid_y = y + m[SY];
		if (grid_y < 0 || grid_y >= 31*8) { return; }
		final int tile_y = grid_y % 8;
		final int grid_ptr = m[GP] + (grid_y / 8) * (41 + m[GS]);

		for(int x = 0; x < 41; x++) {
			final int tile_id = m[grid_ptr + x];
			if (tile_id >= 0 && hiz == ((tile_id & GRID_Z_MASK) != 0)) {
				drawTile(y, x*8 - m[SX], tile_id & ~GRID_Z_MASK, tile_y);
			}
		}
	}

	public void drawRow(int y) {
		for(int x = 0; x < 320; x++) { p[x + (y * 320)] = m[CL]; }
		drawGrid(false, y);

		int base = m[SP];
		for(int sprite = 0; sprite < 256; ++sprite) {
			final int status = m[base + 0];
			final int tile   = m[base + 1];
			final int px     = m[base + 2];
			final int py     = m[base + 3];
			//drawSprite(y, tile, status, px - m[SX], py - m[SY]);
			base += 4;
		}

		drawGrid(true, y);
	}

	public void sync() {
		if (m[RV] == 0) {
			for (int y = 0; y < 240; ++y) {
				drawRow(y);
			}
		} else {
			rpush(m[PC]);
			m[PC] = m[RV];
			for (int y = 0; y < 240; ++y) {
				while(m[PC] != -1 && m[m[PC]] != OP_SYNC) { tick(); }
				if (m[PC] != -1) { ++m[PC]; }
				drawRow(y);
			}
		}

		if (soundLine != null && apointer > 0) {
			soundLine.write(abuffer, 0, apointer);
			apointer = 0;
		}
	}
}
