/*
 *  This file is part of the Haven & Hearth game client.
 *  Copyright (C) 2009 Fredrik Tolf <fredrik@dolda2000.com>, and
 *                     Björn Johannessen <johannessen.bjorn@gmail.com>
 *
 *  Redistribution and/or modification of this file is subject to the
 *  terms of the GNU Lesser General Public License, version 3, as
 *  published by the Free Software Foundation.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  Other parts of this source tree adhere to other copying
 *  rights. Please see the file `COPYING' in the root directory of the
 *  source tree for details.
 *
 *  A copy the GNU Lesser General Public License is distributed along
 *  with the source tree of which this file is a part in the file
 *  `doc/LPGL-3'. If it is missing for any reason, please see the Free
 *  Software Foundation's website at <http://www.fsf.org/>, or write
 *  to the Free Software Foundation, Inc., 59 Temple Place, Suite 330,
 *  Boston, MA 02111-1307 USA
 */

package haven;

import java.util.*;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.*;

public class Connection {
    private static final double ACK_HOLD = 0.030;
    public final SocketAddress server;
    public final String username;
    public final Glob glob;
    private final DatagramChannel sk;
    private final Selector sel;
    private final SelectionKey key;
    private Worker worker;
    private int tseq;

    public interface MessageHandler {
	public void handle(Message msg);
    }

    private Connection(SocketAddress server, String username) {
	this.server = server;
	this.username = username;
	this.glob = new Glob(null);
	try {
	    this.sk = DatagramChannel.open();
	    sk.connect(server);
	    sk.configureBlocking(false);

	    sel = Selector.open();
	    key = sk.register(sel, SelectionKey.OP_READ);
	} catch(IOException e) {
	    throw(new RuntimeException(e));
	}
    }

    private class Worker extends HackThread {
	private Task init;
	
	private Worker(Task init) {
	    super("Connection worker");
	    setDaemon(true);
	    this.init = init;
	}

	public void run() {
	    Task task = init;
	    init = null;
	    try {
		/* Poor man's tail recursion. */
		while(task != null)
		    task = task.run();
	    } finally {
		try {
		    sk.close();
		    sel.close();
		} catch(IOException e) {
		    throw(new RuntimeException(e));
		}
	    }
	}
    }

    public interface Task {
	public Task run();
    }

    private void start(Task init) {
	synchronized(this) {
	    if(worker != null)
		throw(new IllegalStateException());
	    worker = new Worker(init);
	    worker.start();
	}
    }

    private final ByteBuffer recvbuf = ByteBuffer.allocate(65536);
    private PMessage recv() throws IOException {
	recvbuf.clear();
	int ret = sk.read(recvbuf);
	if(ret < 0) {
	    throw(new Error());
	} else if(ret == 0) {
	    return(null);
	} else {
	    recvbuf.flip();
	    byte type = recvbuf.get();
	    byte[] buf = new byte[recvbuf.remaining()];
	    recvbuf.get(buf);
	    return(new PMessage(type, buf));
	}
    }

    public void send(ByteBuffer msg) {
	try {
	    System.err.println(msg);
	    sk.write(msg);
	} catch(IOException e) {
	    /* Generally assume errors are transient and treat them as
	     * packet loss, but are there perhaps errors that
	     * shouldn't be considered transient? */
	}
    }

    public void send(PMessage msg) {
	ByteBuffer buf = ByteBuffer.allocate(msg.size() + 1);
	buf.put((byte)msg.type);
	msg.fin(buf);
	buf.flip();
	send(buf);
    }

    public void close() {
	if(worker == null)
	    throw(new IllegalStateException());
	worker.interrupt();
    }

    private boolean select(double timeout) throws IOException {
	sel.selectedKeys().clear();
	sel.select((long)(timeout * 1000));
	return(key.isReadable());
    }

    private void wake() {
	sel.wakeup();
    }

    private final List<RMessage> pending = new LinkedList<>();

    private class Connect implements Task {
	private final PMessage msg;
	private int result = -1;
	private String message;

	private Connect(byte[] cookie, Object... args) {
	    msg = new PMessage(Session.MSG_SESS);
	    msg.adduint16(2);
	    msg.addstring("Hafen");
	    msg.adduint16(Session.PVER);
	    msg.addstring(username);
	    msg.adduint16(cookie.length);
	    msg.addbytes(cookie);
	    msg.addlist(args);
	}

	public Task run() {
	    int retries = 0;
	    double last = 0;
	    try {
		while(true) {
		    double now = Utils.rtime();
		    if(now - last > 2) {
			if(++retries > 5) {
			    result = Session.SESSERR_CONN;
			    return(null);
			}
			send(msg);
			last = now;
		    }
		    try {
			if(select(Math.max(0.0, last + 2 - now))) {
			    PMessage msg = recv();
			    if((msg != null) && (msg.type == Session.MSG_SESS)) {
				int error = msg.uint8();
				if(error == 0) {
				    return(new Main());
				} else {
				    this.result = error;
				    if(error == Session.SESSERR_MESG)
					message = msg.string();
				    return(null);
				}
			    }
			}
		    } catch(ClosedByInterruptException e) {
			return(null);
		    } catch(IOException e) {
			throw(new RuntimeException(e));
		    }
		}
	    } finally {
		synchronized(this) {
		    if(result < 0)
			result = Session.SESSERR_CONN;
		    notifyAll();
		}
	    }
	}
    }

    private static class ObjAck {
	long id;
	int frame;
	double frecv, lrecv;

	ObjAck(long id, int frame, double recv) {
	    this.id = id;
	    this.frame = frame;
	    this.frecv = this.lrecv = recv;
	}
    }

    private class Main implements Task {
	private final Map<Short, RMessage> waiting = new HashMap<>();
	private final Map<Long, ObjAck> objacks = new HashMap<>();
	private double now, lasttx;
	private short rseq, ackseq;
	private double acktime = -1;

	private void handlerel(RMessage msg) {
	}

	private void gotrel(RMessage msg) {
	    short sd = (short)(msg.seq - rseq);
	    if(sd == 0) {
		short lastack;
		do {
		    handlerel(msg);
		    lastack = rseq++;
		    msg = waiting.remove(rseq);
		} while(msg != null);
		sendack(lastack);
	    } else if(sd > 0) {
		waiting.put((short)msg.seq, msg);
	    }
	}

	private void sendack(short seq) {
	    if(acktime < 0)
		acktime = now;
	    ackseq = seq;
	}

	private void gotack(short seq) {
	    synchronized(pending) {
		for(Iterator<RMessage> i = pending.iterator(); i.hasNext();) {
		    RMessage msg = i.next();
		    short sd = (short)(msg.seq - seq);
		    if(sd <= 0)
			i.remove();
		    else
			break;
		}
	    }
	}

	private void gotmapdata(Message msg) {
	    glob.map.mapdata(msg);
	}

	private void gotobjdata(Message msg) {
	    while(!msg.eom()) {
		int fl = msg.uint8();
		long id = msg.uint32();
		int fr = msg.int32();
		glob.oc.receive(fl, id, fr, msg);
		ObjAck ack = objacks.get(id);
		if(ack == null) {
		    objacks.put(id, new ObjAck(id, fr, now));
		} else {
		    if(fr > ack.frame)
			ack.frame = fr;
		    ack.lrecv = now;
		}
	    }
	}

	private void handlemsg(PMessage msg) {
	    switch(msg.type) {
	    case Session.MSG_SESS: {
		break;
	    }
	    case Session.MSG_REL: {
		int seq = msg.uint16();
		while(!msg.eom()) {
		    int type = msg.uint8();
		    RMessage rmsg;
		    if((type & 0x80) != 0) {
			rmsg = new RMessage(type & 0x7f, msg.bytes(msg.uint16()));
		    } else {
			rmsg = new RMessage(type, msg.bytes());
		    }
		    rmsg.seq = seq++;
		    gotrel(rmsg);
		}
		break;
	    }
	    case Session.MSG_ACK: {
		gotack((short)msg.uint16());
		break;
	    }
	    case Session.MSG_MAPDATA: {
		gotmapdata(msg);
		break;
	    }
	    case Session.MSG_OBJDATA: {
		gotobjdata(msg);
		break;
	    }
	    }
	}

	private double min2(double a, double b) {
	    return((a < 0) ? b : Math.min(a, b));
	}

	private double sendpending() {
	    double mint = -1;
	    synchronized(pending) {
		for(RMessage msg : pending) {
		    double txtime;
		    if(msg.retx == 0)
			txtime = 0;
		    else if(msg.retx <= 1)
			txtime = 0.08;
		    else if(msg.retx <= 3)
			txtime = 0.20;
		    else if(msg.retx <= 9)
			txtime = 0.62;
		    else
			txtime = 2.00;
		    txtime = msg.last + txtime;
		    if(now >= txtime) {
			PMessage rmsg = new PMessage(Session.MSG_REL);
			rmsg.adduint16(msg.seq).adduint8(msg.type).addbytes(msg.fin());
			send(rmsg);
			msg.last = now;
			msg.retx++;
			lasttx = now;
		    } else {
			mint = min2(mint, txtime);
		    }
		}
	    }
	    return(mint);
	}

	private double sendobjacks() {
	    double mint = -1;
	    PMessage msg = null;
	    for(Iterator<ObjAck> i = objacks.values().iterator(); i.hasNext();) {
		ObjAck ack = i.next();
		double txtime = Math.min(ack.lrecv + 0.08, ack.frecv + 0.5);
		if(txtime >= now) {
		    if(msg == null) {
			msg = new PMessage(Session.MSG_OBJACK);
		    } else if(msg.size() > 1000 - 8) {
			send(msg);
			msg = new PMessage(Session.MSG_OBJACK);
		    }
		    msg.adduint32(ack.id);
		    msg.addint32(ack.frame);
		    i.remove();
		} else {
		    mint = min2(mint, txtime);
		}
	    }
	    if(msg != null) {
		send(msg);
		lasttx = now;
	    }
	    return(mint);
	}

	public Task run() {
	    lasttx = now = Utils.rtime();
	    double pendto = now;
	    while(true) {
		double to = 5 - (now - lasttx);
		if(acktime > 0)
		    to = Math.min(to, acktime + ACK_HOLD - now);
		if(pendto >= 0)
		    to = Math.min(to, pendto - now);

		try {
		    Utils.checkirq();
		    if(select(to)) {
			PMessage msg;
			while((msg = recv()) != null) {
			    if(msg.type == Session.MSG_CLOSE)
				return(new Close(true));
			    handlemsg(msg);
			}
		    }
		} catch(ClosedByInterruptException | InterruptedException e) {
		    return(new Close(false));
		} catch(IOException e) {
		    throw(new RuntimeException(e));
		}
		now = Utils.rtime();

		pendto = min2(sendpending(), sendobjacks());
		if((acktime > 0) && (now - acktime >= ACK_HOLD)) {
		    send((PMessage)new PMessage(Session.MSG_ACK).adduint16(ackseq));
		    acktime = -1;
		    lasttx = now;
		}
		if(now - lasttx >= 5) {
		    send(new PMessage(Session.MSG_BEAT));
		    lasttx = now;
		}
	    }
	}
    }

    private class Close implements Task {
	private boolean sawclose;

	private Close(boolean sawclose) {
	    this.sawclose = sawclose;
	}

	public Task run() {
	    int retries = 0;
	    double last = 0;
	    while(true) {
		double now = Utils.rtime();
		if(now - last > 0.5) {
		    if(++retries > 5)
			return(null);
		    send(new PMessage(Session.MSG_CLOSE));
		    last = now;
		}
		try {
		    if(select(Math.max(0.0, last + 0.5 - now))) {
			PMessage msg = recv();
			if((msg != null) && (msg.type == Session.MSG_CLOSE))
			    sawclose = true;
		    }
		} catch(ClosedByInterruptException e) {
		    continue;
		} catch(IOException e) {
		    throw(new RuntimeException(e));
		}
		if(sawclose)
		    return(null);
	    }
	}
    }

    public void queuemsg(PMessage pmsg) {
	RMessage msg = new RMessage(pmsg);
	synchronized(pending) {
	    msg.seq = tseq;
	    tseq = (tseq + 1) & 0xffff;
	    pending.add(msg);
	}
	wake();
    }

    public static class SessionError extends RuntimeException {
	public SessionError(String reason) {
	    super(reason);
	}
    }
    public static class SessionAuthError extends SessionError {
	public SessionAuthError() {super("Invalid authentication token");}
    }
    public static class SessionBusyError extends SessionError {
	public SessionBusyError() {super("Already logged in");}
    }
    public static class SessionConnError extends SessionError {
	public SessionConnError() {super("Could not connect to server");}
    }
    public static class SessionPVerError extends SessionError {
	public SessionPVerError() {super("This client is too old");}
    }
    public static class SessionExprError extends SessionError {
	public SessionExprError() {super("Authentication token expired");}
    }

    public static Connection connect(SocketAddress server, String username, byte[] cookie, Object... args) throws InterruptedException {
	Connection conn = new Connection(server, username);
	Connect init = conn.new Connect(cookie, args);
	conn.start(init);
	try {
	    synchronized(init) {
		while(init.result < 0)
		    init.wait();
	    }
	} catch(InterruptedException e) {
	    conn.close();
	    throw(e);
	}
	if(init.result == 0)
	    return(conn);
	conn.close();
	switch(init.result) {
	case Session.SESSERR_AUTH:
	    throw(new SessionAuthError());
	case Session.SESSERR_BUSY:
	    throw(new SessionBusyError());
	case Session.SESSERR_CONN:
	    throw(new SessionConnError());
	case Session.SESSERR_PVER:
	    throw(new SessionPVerError());
	case Session.SESSERR_EXPR:
	    throw(new SessionExprError());
	case Session.SESSERR_MESG:
	    throw(new SessionError(init.message));
	default:
	    throw(new SessionError("Connection failed: " + init.result));
	}
    }
}
