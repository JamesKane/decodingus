-- !Ups

INSERT INTO publication_search_configs (name, search_query, concepts, enabled) VALUES
('Forensic Anthropology and Bioarchaeology Studies', 'forensic anthropology bioarchaeology', '["https://api.openalex.org/concepts/wikidata:Q28065", "https://api.openalex.org/concepts/wikidata:Q13404081"]'::jsonb, TRUE),
('Archaeology and Ancient Environmental Studies', 'archaeology ancient environmental', '["https://api.openalex.org/concepts/wikidata:Q23498", "https://api.openalex.org/concepts/wikidata:Q1561862"]'::jsonb, TRUE),
('Forensic and Genetic Research', 'forensic genetic research', '["https://api.openalex.org/concepts/wikidata:Q495304", "https://api.openalex.org/concepts/wikidata:Q69953209"]'::jsonb, TRUE);

-- !Downs

DELETE FROM publication_search_configs WHERE name IN (
'Forensic Anthropology and Bioarchaeology Studies',
'Archaeology and Ancient Environmental Studies',
'Forensic and Genetic Research'
);
