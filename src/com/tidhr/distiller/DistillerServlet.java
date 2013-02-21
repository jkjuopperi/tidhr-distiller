package com.tidhr.distiller;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import opennlp.tools.namefind.NameFinderME;
import opennlp.tools.namefind.TokenNameFinderModel;
import opennlp.tools.sentdetect.SentenceDetectorME;
import opennlp.tools.sentdetect.SentenceModel;
import opennlp.tools.tokenize.TokenizerME;
import opennlp.tools.tokenize.TokenizerModel;
import opennlp.tools.util.InvalidFormatException;
import opennlp.tools.util.Span;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import com.amazonaws.util.json.JSONException;
import com.amazonaws.util.json.JSONObject;

import de.l3s.boilerpipe.BoilerpipeProcessingException;
import de.l3s.boilerpipe.document.TextDocument;
import de.l3s.boilerpipe.extractors.ArticleExtractor;
import de.l3s.boilerpipe.sax.BoilerpipeSAXInput;
import de.l3s.boilerpipe.sax.HTMLFetcher;

public class DistillerServlet extends HttpServlet {
	private static final Logger log = LoggerFactory.getLogger(DistillerServlet.class);
	private static final long serialVersionUID = 1L;
	private static final String OUTPUT_CHARSET = "UTF-8";

	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		try {
			log.info("GET {}", req.getRequestURI());

			// Input
			TextDocument document = fetchTextDocument(req);
			String title = document.getTitle();
			String text = document.getContent();
			document = null;

			makeJsonReplyWithTags(resp, title, text);

		} catch (Exception e) {
			throw new ServletException(e);
		}

	}

	private TextDocument fetchTextDocument(HttpServletRequest req) throws MalformedURLException, SAXException, IOException, BoilerpipeProcessingException {
		URL url = new URL(req.getParameter("url"));
		BoilerpipeSAXInput boilerPipeInput = new BoilerpipeSAXInput(HTMLFetcher.fetch(url).toInputSource());
		// Parse to text document
		TextDocument document = boilerPipeInput.getTextDocument();

		// Cleanify the document
		ArticleExtractor articleExtractor = ArticleExtractor.getInstance();
		articleExtractor.process(document);
		return document;
	}

	@Override
	protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		try {

			log.info("POST {} ({} bytes)", req.getRequestURI(), req.getContentLength());

			// Input
			TextDocument document = extractTextDocument(req);
			String title = document.getTitle();
			String text = document.getContent();
			document = null;

			makeJsonReplyWithTags(resp, title, text);

		} catch (Exception e) {
			throw new ServletException(e);
		}
	}

	private void makeJsonReplyWithTags(HttpServletResponse resp, String title, String text) throws JSONException, IOException, InvalidFormatException, UnsupportedEncodingException {
		// Make JSON object
		JSONObject outputObject = new JSONObject();

		// Title
		if (title != null) {
			log.info("Title: {}", title);
			outputObject.put("title", title);
		}
		title = null;

		// body
		outputObject.put("content", text);

		// Find sentences
		String[] sentences = splitToSentences(text);
		text = null;

		// Find words
		ArrayList<String[]> tokenizedSentences = tokenizeSentences(sentences);
		sentences = null;

		// Find persons
		extractNamesToJSON(tokenizedSentences, outputObject, "persons", "en-ner-person.bin");
		extractNamesToJSON(tokenizedSentences, outputObject, "locations", "en-ner-location.bin");
		extractNamesToJSON(tokenizedSentences, outputObject, "organizations", "en-ner-organization.bin");

		// Output
		resp.setContentType("application/json");
		resp.setCharacterEncoding(OUTPUT_CHARSET);
		ServletOutputStream outputStream = resp.getOutputStream();
		outputStream.write(outputObject.toString().getBytes(OUTPUT_CHARSET));
		outputStream.close();
	}

	private void extractNamesToJSON(ArrayList<String[]> tokenizedSentences, JSONObject jsonObj, String key, String modelFile) throws IOException, InvalidFormatException, JSONException {
		Set<String> names = extractNames(tokenizedSentences, modelFile);
		if (names.size() > 0) {
			jsonObj.put(key, names);
		}
	}

	private ArrayList<String[]> tokenizeSentences(String[] sentences) throws IOException, InvalidFormatException {
		ArrayList<String[]> tokenizedSentences = new ArrayList<String[]>();
		TokenizerModel tokenizerModel = new TokenizerModel(getClass().getResourceAsStream("en-token.bin"));
		TokenizerME tokenizer = new TokenizerME(tokenizerModel);
		for (String sentence : sentences) {
			String[] tokens = tokenizer.tokenize(sentence);
			tokenizedSentences.add(tokens);
		}
		return tokenizedSentences;
	}

	private String[] splitToSentences(String text) throws IOException, InvalidFormatException {
		SentenceModel sentenceModel = new SentenceModel(getClass().getResourceAsStream("en-sent.bin"));
		SentenceDetectorME sentenceDetector = new SentenceDetectorME(sentenceModel);
		String[] sentences = sentenceDetector.sentDetect(text);
		sentenceDetector = null;
		sentenceModel = null;
		return sentences;
	}

	private TextDocument extractTextDocument(HttpServletRequest req) throws IOException, SAXException, BoilerpipeProcessingException {
		ServletInputStream inputStream = req.getInputStream();
		BoilerpipeSAXInput boilerPipeInput = new BoilerpipeSAXInput(new InputSource(
				new InputStreamReader(inputStream)));
		// Parse to text document
		TextDocument document = boilerPipeInput.getTextDocument();
		inputStream.close();

		// Cleanify the document
		ArticleExtractor articleExtractor = ArticleExtractor.getInstance();
		articleExtractor.process(document);
		return document;
	}

	private Set<String> extractNames(ArrayList<String[]> tokenizedSentences, String modelFile) throws IOException, InvalidFormatException {

		Set<String> names = new HashSet<String>();

		// Find out things about the document
		TokenNameFinderModel personModel = new
				TokenNameFinderModel(getClass().getResourceAsStream(modelFile));
		NameFinderME nameFinder = new NameFinderME(personModel);

		// Find stuff
		for (String[] sentence : tokenizedSentences) {
			Span[] nameSpans = nameFinder.find(sentence);
			String[] locationStrings = Span.spansToStrings(nameSpans, sentence);
			for (String personString : locationStrings) {
				names.add(personString);
			}
		}

		return names;
	}
}
