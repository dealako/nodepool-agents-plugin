package com.rackspace.jenkins_nodepool;

import java.io.IOException;
import java.io.OutputStream;

public class DelegateNoCloseOutputStream extends OutputStream {
    private OutputStream out;

    public DelegateNoCloseOutputStream(OutputStream out) {
        this.out = out;
    }

    @Override
    public void write(int b) throws IOException {
        if (out != null) out.write(b);
    }

    @Override
    public void close() throws IOException {
        out = null;
    }

    @Override
    public void flush() throws IOException {
        if (out != null) out.flush();
    }

    @Override
    public void write(byte[] b) throws IOException {
        if (out != null) out.write(b);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        if (out != null) out.write(b, off, len);
    }
}
