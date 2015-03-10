package uk.ac.ebi.pride.proteomes.index;

import org.apache.solr.client.solrj.SolrServer;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.response.SolrPingResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import uk.ac.ebi.pride.proteomes.db.core.api.modification.ModificationLocation;
import uk.ac.ebi.pride.proteomes.db.core.api.peptide.PeptideRepository;
import uk.ac.ebi.pride.proteomes.db.core.api.peptide.Peptiform;
import uk.ac.ebi.pride.proteomes.db.core.api.peptide.SymbolicPeptide;
import uk.ac.ebi.pride.proteomes.db.core.api.peptide.group.PeptideGroup;
import uk.ac.ebi.pride.proteomes.db.core.api.peptide.group.PeptideGroupRepository;
import uk.ac.ebi.pride.proteomes.db.core.api.peptide.protein.PeptideProtein;
import uk.ac.ebi.pride.proteomes.db.core.api.peptide.protein.PeptideProteinRepository;
import uk.ac.ebi.pride.proteomes.db.core.api.protein.groups.EntryGroup;
import uk.ac.ebi.pride.proteomes.db.core.api.protein.groups.GeneGroup;
import uk.ac.ebi.pride.proteomes.db.core.api.protein.groups.ProteinGroup;
import uk.ac.ebi.pride.proteomes.index.model.PeptiForm;
import uk.ac.ebi.pride.proteomes.index.service.ProteomesIndexService;

import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author florian@ebi.ac.uk
 */
public class ProteomesIndexer {
    // ToDo: this is not flexible nor efficient, for production a better way should be found
    //       for example: primary data load (peptiforms) and separate annotations pipelines

    private static Logger logger = LoggerFactory.getLogger(ProteomesIndexer.class.getName());

    private static final int pageSize = 1000;

    private static final int MAX_PING_TIME = 1000;
    private static final int MAX_ATTEMPTS = 5;
    private int attempts = 0;

    private ProteomesIndexService indexService;
    private PeptideRepository peptideRepository;
    private PeptideGroupRepository peptideGroupRepository;
    private PeptideProteinRepository pepProtRepo;
    private SolrServer solrServer;

    public ProteomesIndexer(ProteomesIndexService indexService,
                            PeptideRepository peptideRepository,
                            PeptideGroupRepository peptideGroupRepository,
                            PeptideProteinRepository peptideProteinRepository,
                            SolrServer solrServer) {
        this.indexService = indexService;
        this.peptideRepository = peptideRepository;
        this.peptideGroupRepository = peptideGroupRepository;
        this.pepProtRepo = peptideProteinRepository;
        this.solrServer = solrServer;
    }

    public void deleteAll() {
        indexService.deleteAll();
    }

    /**
     * Notes:
     *
     * - Proteomes Peptides are split in two forms:
     *     Peptiforms       - the representation of a chemical molecule
     *                        (sequence, modifications, species)
     *     SymbolicPeptides - a representation for all Peptiforms with
     *                        the same sequence and species
     *
     * - only SymbolicPeptides are mapped to Proteins, so we need them to
     *   establish the peptide-protein mapping
     *
     * - only Peptiforms contain modification data, so we need those too to
     *   list modifications
     *
     * - we need to separately query for protein groups
     *
     * - we currently have two types of protein groups:
     *   - UP Entry groups - proteins grouped by UniProt entry (all isoforms)
     *   - Gene groups     - proteins grouped by their encoding gene
     *
     */
    public void indexBySymbolicPeptides(boolean simulation) {

        logger.info("Start indexing Proteomes data");
        // make sure we are working on an empty index
        if (!simulation) {
            logger.debug("Removing all entries");
            indexService.deleteAll();
        }

        int page = 0;
        PageRequest request;
        boolean done = false;

        logger.debug("Starting to retrieve data");
        // loop over the data in pages until we are done with all
        while (!done) {
            logger.debug("Retrieving page : " + page);
            request = new PageRequest(page, pageSize);

            long dbStart = System.currentTimeMillis();
            List<SymbolicPeptide> peptidePage = peptideRepository.findAllSymbolicPeptides(request);
            logger.debug("\tMain DB query took [ms] : " + (System.currentTimeMillis() - dbStart));
//            done = (page >= peptidePage.getTotalPages() - 1); // stop criteria when using paged results
            if (peptidePage.size() < pageSize) { done = true; } // stop criteria when using result lists
//            done = (page >= 9); // testing with 10 pages (0-9)

//            List<Peptide> peptideList = peptidePage.getContent();
            long convStart = System.currentTimeMillis();
            List<PeptiForm> peptiFormList = convert(peptidePage);

            logger.debug("\tConversion of " + peptiFormList.size() +" records took [ms] : " + (System.currentTimeMillis() - convStart));

            if (!simulation) {

                solrServerCheck();

                long indexStart = System.currentTimeMillis();
                indexService.save(peptiFormList);
                logger.debug("\tIndex save took [ms] : " + (System.currentTimeMillis() - indexStart));
            }

            // increase the page number
            page++;
        }

        logger.info("Indexing complete.");
    }

    private void solrServerCheck() {
        if (attempts < MAX_ATTEMPTS) {
            try {
                SolrPingResponse response = solrServer.ping();
                long elapsedTime = response.getElapsedTime();
                if (elapsedTime > MAX_PING_TIME) {
                    logger.debug("Solr response too slow: " + elapsedTime + ". Attempt: " + attempts + ". Waiting... ");
                    waitSecs(30);
                    attempts ++;
                } else {
                    attempts = 0;
                }
            } catch (SolrServerException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            }
        } else {
            throw new IllegalStateException("Solr server not responding in time. Aborting.");
        }
    }


    private List<PeptiForm> convert(List<SymbolicPeptide> peptideList) {
        List<PeptiForm> peptiFormList = new ArrayList<PeptiForm>(peptideList.size());
        for (SymbolicPeptide symbolicPeptide : peptideList) {
            // retrieve the mapped Proteins, which are the same for all Peptiforms from the same SymbolicPeptide
            List<String> mappedProteins = getProteinIdsFromSymbolicPeptide(symbolicPeptide);

            // find all Peptiforms for the current SymbolicPeptide
            long start = System.currentTimeMillis();
            List<Peptiform> peptiforms = peptideRepository.findPeptiformBySequenceAndTaxid(symbolicPeptide.getSequence(), symbolicPeptide.getTaxid());
            long time = System.currentTimeMillis() - start;
            if (time > 50) {
                logger.debug("\t\tPeptiform DB query outside expected range normal [1-5ms]: " + time + "ms");
            }

            // for each of the DB Peptiforms create a Solr PeptiForm for indexing
            for (Peptiform peptiform : peptiforms) {
                PeptiForm peptiForm = convert(peptiform);
                // add Protein mappings
                peptiForm.setProteins(mappedProteins);
                peptiForm.setNumProteins(mappedProteins.size());
                // add the groups
                addGroupsToPeptiForm(peptiForm, symbolicPeptide.getPeptideId());

                peptiFormList.add(peptiForm);
            }

        }
        return peptiFormList;
    }

    private void addGroupsToPeptiForm(PeptiForm peptiForm, long pepID) {
        List<PeptideGroup> peptideGroups = peptideGroupRepository.findByPeptidePeptideId(pepID);

        Set<String> upEntryGroups = new HashSet<String>();
        Set<String> geneGroups = new HashSet<String>();
        StringBuilder groupDescriptions = new StringBuilder();// all keywords, description and additional text to SEARCH on goes here

        for (PeptideGroup peptideGroup : peptideGroups) {
            ProteinGroup proteinGroup = peptideGroup.getProteinGroup();
            groupDescriptions.append(proteinGroup.getDescription()); // for searching purposes
            groupDescriptions.append("\t"); // make sure we are not concatenating two words from two descriptions
            if (proteinGroup instanceof EntryGroup) {
                upEntryGroups.add(proteinGroup.getId());
            } else if (proteinGroup instanceof GeneGroup) {
                geneGroups.add(proteinGroup.getId());
            } else {
                // error, we log a warning, but ignore it for now
                logger.warn("Found unknown ProteinGroup type: " + proteinGroup.getClass());
            }
        }
        peptiForm.setUpGroups(new ArrayList<String>(upEntryGroups));
        peptiForm.setNumUpGroups(upEntryGroups.size());
        peptiForm.setGeneGroups(new ArrayList<String>(geneGroups));
        peptiForm.setNumGeneGroups(geneGroups.size());
        peptiForm.setGroupDescs(groupDescriptions.toString());
    }

    private static PeptiForm convert(Peptiform peptiform) {
        PeptiForm peptiForm = new PeptiForm();
        peptiForm.setId(peptiform.getPeptideRepresentation());
        peptiForm.setSequence(peptiform.getSequence());
        peptiForm.setTaxid(peptiform.getTaxid());
        peptiForm.setSpecies(getSpeciesForTaxid(peptiform.getTaxid()));
//        peptiForm.setMods(new ArrayList<String>(getModNamesFromPeptiform(peptiform))); // session error
        peptiForm.setMods(new ArrayList<String>(getModsFromPeptiformParsingRepresentation(peptiform.getPeptideRepresentation())));
        return peptiForm;
    }

    private static Set<String> getModNamesFromPeptiform(Peptiform peptiform) {
        Set<String> modNames = new HashSet<String>();
        if (peptiform != null && peptiform.getModificationLocations() != null) {
            for (ModificationLocation modLoc : peptiform.getModificationLocations()) {
                int modId = Integer.parseInt(modLoc.getModId());
                modNames.add(getPrideModNameForId(modId));
            }
        }
        return modNames;
    }

    private static String getSpeciesForTaxid(int taxid) {
        // ToDo: map taxid to species names dynamically in separate util
        switch (taxid) {
            case 9606 : return "Homo sapiens (Human)";
            case 10090: return "Mus musculus (Mouse)";
            case 10116: return "Rattus norvegicus (Rat)";
            case 3702 : return "Arabidopsis thaliana (Mouse-ear cress)";
            default   : return "unknown";
        }
    }

    private static String getPrideModNameForId(int modId) {
        switch (modId) {
            case  1 : return "Acetylation";
            case  2 : return "Amidation";
            case  3 : return "Biotinylation";
            case  4 : return "Phosphorylation";
            case  8 : return "Deamidation";
            case 12 : return "Dehydratation";
            case 15 : return "Oxidation";
            case 16 : return "Deamination";
            case 20 : return "Monomethylation";
            case 21 : return "Methylthio";
            case 22 : return "Sulfo";
            case 23 : return "Lipoyl";
            case 24 : return "Farnesylation";
            case 25 : return "Myristoylation";
            case 26 : return "Pyridoxal_phosphate";
            case 27 : return "Palmitoylation";
            case 28 : return "Geranyl_geranyl";
            case 29 : return "Phosphopantetheine";
            case 30 : return "Flavin_adenine_dinucleotide";
            case 32 : return "Formylation";
            case 39 : return "Carboxylation";
            case 40 : return "Dioxidation";
            case  5 :
            case  6 :
            case  7 :
            case  9 :
            case 10 :
            case 11 :
            case 13 :
            case 14 :
            case 17 :
            case 18 :
            case 19 :
            case 31 :
            case 33 :
            case 34 :
            case 35 :
            case 36 :
            case 37 :
            case 38 :
            case 41 :
            case 42 :
            case 43 : return "NON_BIO";
            default : return "unknown";
        }
    }

    private List<String> getProteinIdsFromSymbolicPeptide(SymbolicPeptide symbolicPeptide) {
        List<String> proteinAccs = new ArrayList<String>();
        // retrieving the proteins directly from the symbolic peptide using the lazy collection
        // causes issues with memory, probably a memory leak or not optimal usage of the lazy loading
//        Collection<PeptideProtein> proteins = symbolicPeptide.getProteins();
        // we retrieve the protein mappings for the peptide using separate repo calls, to avoid memory issues
        Collection<PeptideProtein> proteins = pepProtRepo.findByPeptidePeptideId(symbolicPeptide.getPeptideId());
        if (proteins != null) {
            for (PeptideProtein protein : proteins) {
                proteinAccs.add(protein.getProteinAccession());
            }
        }
        return proteinAccs;
    }

    private static Set<String> getModsFromPeptiformParsingRepresentation(String representation) {
        Set<String> modNames = new HashSet<String>();

        Matcher matcher = Pattern.compile("\\d+,\\d+").matcher(representation);

        while(matcher.find()) {
            String modLoc = matcher.group();
            String[] locAndMod = modLoc.split(",");
            int modId = Integer.parseInt(locAndMod[1]);
            modNames.add(getPrideModNameForId(modId));

        }

        return modNames;
    }

    private static void waitSecs(int secondsToWait) {
        try {
            Thread.sleep(secondsToWait * 1000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

}
