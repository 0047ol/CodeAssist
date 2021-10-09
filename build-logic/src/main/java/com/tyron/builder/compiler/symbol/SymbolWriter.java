package com.tyron.builder.compiler.symbol;


import com.google.common.base.Charsets;
import com.google.common.base.Splitter;
import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Lists;
import com.google.common.collect.Table;
import com.google.common.io.Files;

import org.apache.commons.io.FileUtils;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A class to write R.java classes based on data read from text symbol files generated by
 * AAPT/AAPT2 with the --output-text-symbols option.
 */
public class SymbolWriter {

    private final String mOutFolder;
    private final String mPackageName;
    private final List<SymbolLoader> mSymbols = Lists.newArrayList();
    private final SymbolLoader mValues;

    public SymbolWriter(String outFolder, String packageName, SymbolLoader values) {
        mOutFolder = outFolder;
        mPackageName = packageName;
        mValues = values;
    }

    public void addSymbolsToWrite(SymbolLoader symbols) {
        mSymbols.add(symbols);
    }

    private Table<String, String, SymbolLoader.SymbolEntry> getAllSymbols() {
        Table<String, String, SymbolLoader.SymbolEntry> symbols = HashBasedTable.create();

        for (SymbolLoader symbolLoader : mSymbols) {
            symbols.putAll(symbolLoader.getSymbols());
        }

        return symbols;
    }

    public void write() throws IOException {
        Splitter splitter = Splitter.on('.');
        Iterable<String> folders = splitter.split(mPackageName);
        File file = new File(mOutFolder);
        for (String folder : folders) {
            file = new File(file, folder);
        }
        boolean newFile = false;
        if (!file.exists()) {
            newFile = true;
            if (!file.mkdirs()) {
                throw new IOException("Unable to create resource directories for " + file);
            }
        }
        file = new File(file, "R.java");

        StringWriter writer = null;
        try {
            writer = new StringWriter();

            writer.write("/* AUTO-GENERATED FILE. DO NOT MODIFY. \n");
            writer.write(" *\n");
            writer.write(" * This class was automatically generated by the\n");
            writer.write(" * aapt tool from the resource data it found.  It\n");
            writer.write(" * should not be modified by hand.\n");
            writer.write(" */\n");

            writer.write("package ");
            writer.write(mPackageName);
            writer.write(";\n\npublic final class R {\n");

            Table<String, String, SymbolLoader.SymbolEntry> symbols = getAllSymbols();
            Table<String, String, SymbolLoader.SymbolEntry> values = mValues.getSymbols();

            Set<String> rowSet = symbols.rowKeySet();
            List<String> rowList = Lists.newArrayList(rowSet);
            Collections.sort(rowList);

            for (String row : rowList) {
                writer.write("\tpublic static final class ");
                writer.write(row);
                writer.write(" {\n");

                Map<String, SymbolLoader.SymbolEntry> rowMap = symbols.row(row);
                Set<String> symbolSet = rowMap.keySet();
                ArrayList<String> symbolList = Lists.newArrayList(symbolSet);
                Collections.sort(symbolList);

                for (String symbolName : symbolList) {
                    // get the matching SymbolEntry from the values Table.
                    SymbolLoader.SymbolEntry value = values.get(row, symbolName);
                    if (value != null) {
                        writer.write("\t\tpublic static final ");
                        writer.write(value.getType());
                        writer.write(" ");
                        writer.write(value.getName());
                        writer.write(" = ");
                        writer.write(value.getValue());
                        writer.write(";\n");
                    }
                }

                writer.write("\t}\n");
            }

            writer.write("}\n");

            if (newFile) {
                FileUtils.writeStringToFile(file, writer.toString(), Charset.defaultCharset());
            } else {
                String oldContents = FileUtils.readFileToString(file, Charset.defaultCharset());
                if (!oldContents.equals(writer.toString())) {
                    FileUtils.writeStringToFile(file, writer.toString(), Charset.defaultCharset());
                }
            }
        } finally {
            try {
                if (writer != null) {
                    writer.close();
                }
            } catch (IOException ignore) {

            }
        }
    }
}
