package uk.ac.ebi.pride.proteomes.index;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import uk.ac.ebi.pride.proteomes.db.core.api.peptide.Peptide;
import uk.ac.ebi.pride.proteomes.db.core.api.peptide.PeptideRepository;
import uk.ac.ebi.pride.proteomes.db.core.api.peptide.SymbolicPeptide;
import uk.ac.ebi.pride.proteomes.db.core.api.peptide.protein.PeptideProtein;
import uk.ac.ebi.pride.proteomes.index.model.PeptiForm;
import uk.ac.ebi.pride.proteomes.index.service.ProteomesIndexService;

import java.util.*;

/**
 * @author florian@ebi.ac.uk
 */
public class ProteomesIndexer {

    private static Logger logger = LoggerFactory.getLogger(ProteomesIndexer.class.getName());

    private static final int pageSize = 1000;

    private ProteomesIndexService indexService;
    private PeptideRepository peptideRepository;

    public ProteomesIndexer(ProteomesIndexService indexService, PeptideRepository peptideRepository) {
        this.indexService = indexService;
        this.peptideRepository = peptideRepository;
    }

    public void deleteAll() {
        indexService.deleteAll();
    }

    public void indexProteomes() {

        logger.info("Start indexing Proteomes data");
        // make sure we are working on an empty index
        logger.debug("Removing all entries");
        indexService.deleteAll();

        int page = 0;
        PageRequest request;
        boolean done = false;

        // loop over the data in pages until we are done with all
        while (!done) {
            logger.debug("Retrieving page : " + page);
            request = new PageRequest(page, pageSize);

            long dbStart = System.currentTimeMillis();
//            Page<Peptide> peptidePage = peptideRepository.findAll(request);
            List<SymbolicPeptide> peptidePage = peptideRepository.findAllSymbolicPeptides(request);
            logger.debug("DB query took [ms] : " + (System.currentTimeMillis() - dbStart));
//            List<Peptiform> peptidePage = peptideRepository.findAllPeptiforms(request);
//            done = (page >= peptidePage.getTotalPages() - 1);
            done = (page >= 9); // 10 pages (0-9)

//            List<Peptide> peptideList = peptidePage.getContent();
            long convStart = System.currentTimeMillis();
            List<PeptiForm> peptiFormList = convert(peptidePage);
            logger.debug("Conversion took [ms] : " + (System.currentTimeMillis() - convStart));

            long indexStart = System.currentTimeMillis();
            indexService.save(peptiFormList);
            logger.debug("Index save took [ms] : " + (System.currentTimeMillis() - indexStart));

            // increase the page number
            page++;
        }

        logger.info("Indexing complete.");
    }


    private <T extends Peptide> List<PeptiForm> convert(List<T> peptideList) {
        List<PeptiForm> peptiFormList = new ArrayList<PeptiForm>(peptideList.size());
        for (T peptide : peptideList) {
            peptiFormList.add(convert(peptide));
        }
        return peptiFormList;
    }

    private <T extends Peptide> PeptiForm convert(T peptide) {
        PeptiForm peptiForm;
        peptiForm = new PeptiForm();
        peptiForm.setId(peptide.getPeptideRepresentation());
        peptiForm.setTaxid(peptide.getTaxid());
        peptiForm.setSequence(peptide.getSequence());

        // ToDo: maybe add peptide annotations (proteins, genes, mods, scores,...) in separate step
        Collection<PeptideProtein> prots = peptide.getProteins();

        if (prots != null) {
            peptiForm.setNumProteins(prots.size());
            peptiForm.setProteins(getProteinAccs(prots));
        }
        // ToDo: map taxid to species names dynamically in separate util
        switch (peptide.getTaxid()) {
            case 9606 : peptiForm.setSpecies("Homo sapiens (Human)");
                        break;
            case 10090: peptiForm.setSpecies("Mus musculus (Mouse)");
                        break;
            case 10116: peptiForm.setSpecies("Rattus norvegicus (Rat)");
                        break;
            case 3702 : peptiForm.setSpecies("Arabidopsis thaliana (Mouse-ear cress)");
                        break;
            default   : peptiForm.setSpecies("unknown");
        }
        return peptiForm;
    }

    private List<String> getProteinAccs(Collection<PeptideProtein> proteins) {
        List<String> proteinAccs = new ArrayList<String>(proteins.size());
        for (PeptideProtein protein : proteins) {
            proteinAccs.add(protein.getProteinAccession());
        }
        return proteinAccs;
    }

}
