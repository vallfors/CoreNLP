package edu.stanford.nlp.parser.shiftreduce;

import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import edu.stanford.nlp.parser.common.ParserConstraint;
import edu.stanford.nlp.parser.lexparser.EvaluateTreebank;
import edu.stanford.nlp.stats.IntCounter;
import edu.stanford.nlp.tagger.common.Tagger;
import edu.stanford.nlp.trees.Tree;
import edu.stanford.nlp.trees.Treebank;
import edu.stanford.nlp.util.CollectionUtils;
import edu.stanford.nlp.util.Generics;
import edu.stanford.nlp.util.Index;
import edu.stanford.nlp.util.Pair;
import edu.stanford.nlp.util.ReflectionLoading;
import edu.stanford.nlp.util.ScoredComparator;
import edu.stanford.nlp.util.ScoredObject;
import edu.stanford.nlp.util.Timing;
import edu.stanford.nlp.util.Quadruple;
import edu.stanford.nlp.util.concurrent.MulticoreWrapper;
import edu.stanford.nlp.util.concurrent.ThreadsafeProcessor;
import edu.stanford.nlp.util.logging.Redwood;


public class PerceptronModel extends BaseModel  {

  /** A logger for this class */
  private static final Redwood.RedwoodChannels log = Redwood.channels(PerceptronModel.class); // Serializable

  private float learningRate = 1.0f;

  Map<String, Weight> featureWeights;
  final FeatureFactory featureFactory;

  public PerceptronModel(ShiftReduceOptions op, Index<Transition> transitionIndex,
                         Set<String> knownStates, Set<String> rootStates, Set<String> rootOnlyStates) {
    super(op, transitionIndex, knownStates, rootStates, rootOnlyStates);
    this.featureWeights = Generics.newHashMap();

    String[] classes = op.featureFactoryClass.split(";");
    if (classes.length == 1) {
      this.featureFactory = ReflectionLoading.loadByReflection(classes[0]);
    } else {
      FeatureFactory[] factories = new FeatureFactory[classes.length];
      for (int i = 0; i < classes.length; ++i) {
        int paren = classes[i].indexOf('(');
        if (paren >= 0) {
          String arg = classes[i].substring(paren + 1, classes[i].length() - 1);
          factories[i] = ReflectionLoading.loadByReflection(classes[i].substring(0, paren), arg);
        } else {
          factories[i] = ReflectionLoading.loadByReflection(classes[i]);
        }
      }
      this.featureFactory = new CombinationFeatureFactory(factories);
    }
  }

  public PerceptronModel(PerceptronModel other) {
    super(other);
    this.featureFactory = other.featureFactory;

    this.featureWeights = Generics.newHashMap();
    for (String feature : other.featureWeights.keySet()) {
      featureWeights.put(feature, new Weight(other.featureWeights.get(feature)));
    }
  }

  private static final NumberFormat NF = new DecimalFormat("0.00");
  private static final NumberFormat FILENAME = new DecimalFormat("0000");

  public void averageScoredModels(Collection<ScoredObject<PerceptronModel>> scoredModels) {
    if (scoredModels.isEmpty()) {
      throw new IllegalArgumentException("Cannot average empty models");
    }

    log.info("Averaging " + scoredModels.size() + " models with scores");
    for (ScoredObject<PerceptronModel> model : scoredModels) {
      log.info(" " + NF.format(model.score()));
    }
    log.info();

    List<PerceptronModel> models = CollectionUtils.transformAsList(scoredModels, ScoredObject::object);
    averageModels(models);
  }

  public void averageModels(Collection<PerceptronModel> models) {
    if (models.isEmpty()) {
      throw new IllegalArgumentException("Cannot average empty models");
    }

    Set<String> features = Generics.newHashSet();
    for (PerceptronModel model : models) {
      for (String feature : model.featureWeights.keySet()) {
        features.add(feature);
      }
    }

    featureWeights = Generics.newHashMap();
    for (String feature : features) {
      featureWeights.put(feature, new Weight());
    }

    int numModels = models.size();
    for (String feature : features) {
      for (PerceptronModel model : models) {
        if (!model.featureWeights.containsKey(feature)) {
          continue;
        }
        featureWeights.get(feature).addScaled(model.featureWeights.get(feature), 1.0f / numModels);
      }
    }
  }

  /**
   * Iterate over the feature weight map.
   * For each feature, remove all transitions with score of 0.
   * Any feature with no transitions left is then removed
   */
  private void condenseFeatures() {
    Iterator<String> featureIt = featureWeights.keySet().iterator();
    while (featureIt.hasNext()) {
      String feature = featureIt.next();
      Weight weights = featureWeights.get(feature);
      weights.condense();
      if (weights.size() == 0) {
        featureIt.remove();
      }
    }
  }

  private void filterFeatures(Set<String> keep) {
    Iterator<String> featureIt = featureWeights.keySet().iterator();
    while (featureIt.hasNext()) {
      if (!keep.contains(featureIt.next())) {
        featureIt.remove();
      }
    }
  }


  /**
   * Output some random facts about the model
   */
  public void outputStats() {
    log.info("Number of known features: " + featureWeights.size());
    int numWeights = 0;
    for (Map.Entry<String, Weight> stringWeightEntry : featureWeights.entrySet()) {
      numWeights += stringWeightEntry.getValue().size();
    }
    log.info("Number of non-zero weights: " + numWeights);

    int wordLength = 0;
    for (String feature : featureWeights.keySet()) {
      wordLength += feature.length();
    }
    log.info("Total word length: " + wordLength);

    log.info("Number of transitions: " + transitionIndex.size());
  }

  /** Reconstruct the tag set that was used to train the model by decoding some of the features.
   *  This is slow and brittle but should work!  Only if "-" is not in the tag set....
   */
  @Override
  Set<String> tagSet() {
    Set<String> tags = Generics.newHashSet();
    Pattern p1 = Pattern.compile("Q0TQ1T-([^-]+)-.*");
    Pattern p2 = Pattern.compile("S0T-(.*)");
    for (String feat : featureWeights.keySet()) {
      Matcher m1 = p1.matcher(feat);
      if (m1.matches()) {
        tags.add(m1.group(1));
      }
      Matcher m2 = p2.matcher(feat);
      if (m2.matches()) {
        tags.add(m2.group(1));
      }
    }
    // Add the end of sentence tag!
    // The SR model doesn't use it, but other models do and report it.
    // todo [cdm 2014]: Maybe we should reverse the convention here?!?
    tags.add(Tagger.EOS_TAG);
    return tags;
  }

  /** Convenience method: returns one highest scoring transition, without any ParserConstraints */
  private ScoredObject<Integer> findHighestScoringTransition(State state, List<String> features, boolean requireLegal) {
    Collection<ScoredObject<Integer>> transitions = findHighestScoringTransitions(state, features, requireLegal, 1, null);
    if (transitions.isEmpty()) {
      return null;
    }
    return transitions.iterator().next();
  }

  @Override
  public Collection<ScoredObject<Integer>> findHighestScoringTransitions(State state, boolean requireLegal, int numTransitions, List<ParserConstraint> constraints) {
    List<String> features = featureFactory.featurize(state);
    return findHighestScoringTransitions(state, features, requireLegal, numTransitions, constraints);
  }

  private Collection<ScoredObject<Integer>> findHighestScoringTransitions(State state, List<String> features, boolean requireLegal, int numTransitions, List<ParserConstraint> constraints) {
    float[] scores = new float[transitionIndex.size()];
    for (String feature : features) {
      Weight weight = featureWeights.get(feature);
      if (weight == null) {
        // Features not in our index are ignored
        continue;
      }
      weight.score(scores);
    }

    PriorityQueue<ScoredObject<Integer>> queue = new PriorityQueue<>(numTransitions + 1, ScoredComparator.ASCENDING_COMPARATOR);
    for (int i = 0; i < scores.length; ++i) {
      if (!requireLegal || transitionIndex.get(i).isLegal(state, constraints)) {
        queue.add(new ScoredObject<>(i, scores[i]));
        if (queue.size() > numTransitions) {
          queue.poll();
        }
      }
    }

    return queue;
  }

  /**
   * index: the tree to train
   * binarizedTrees: a list of all the training trees we know about, binarized
   * transitionLists: a list of pre-assembled transitions for the trees
   * oracle: if training method ORACLE is used, the oracle to use for predicting transitions
   */
  private Quadruple<List<TrainingUpdate>, Integer, Integer, Pair<Integer, Integer>> trainTree(TrainingExample example, Oracle oracle) {
    int numCorrect = 0;
    int numWrong = 0;

    Tree tree = example.binarizedTree;

    List<TrainingUpdate> updates = Generics.newArrayList();
    Pair<Integer, Integer> firstError = null;

    ReorderingOracle reorderer = null;
    if (op.trainOptions().trainingMethod == ShiftReduceTrainOptions.TrainingMethod.REORDER_ORACLE ||
        op.trainOptions().trainingMethod == ShiftReduceTrainOptions.TrainingMethod.REORDER_BEAM) {
      reorderer = new ReorderingOracle(op, rootOnlyStates);
    }

    // TODO.  This training method seems to be working in that it
    // trains models just like the gold and early termination methods do.
    // However, it causes the feature space to go crazy.  Presumably
    // leaving out features with low weights or low frequencies would
    // significantly help with that.  Otherwise, not sure how to keep
    // it under control.
    if (op.trainOptions().trainingMethod == ShiftReduceTrainOptions.TrainingMethod.ORACLE) {
      State state = example.initialStateFromGoldTagTree();
      while (!state.isFinished()) {
        List<String> features = featureFactory.featurize(state);
        ScoredObject<Integer> prediction = findHighestScoringTransition(state, features, true);
        if (prediction == null) {
          throw new AssertionError("Did not find a legal transition");
        }
        int predictedNum = prediction.object();
        Transition predicted = transitionIndex.get(predictedNum);
        OracleTransition gold = oracle.goldTransition(example, state);
        if (gold.isCorrect(predicted)) {
          numCorrect++;
          if (gold.transition != null && !gold.transition.equals(predicted)) {
            int transitionNum = transitionIndex.indexOf(gold.transition);
            if (transitionNum < 0) {
              // TODO: do we want to add unary transitions which are
              // only possible when the parser has gone off the rails?
              continue;
            }
            updates.add(new TrainingUpdate(features, transitionNum, -1, learningRate));
          }
        } else {
          numWrong++;
          int transitionNum = -1;
          if (gold.transition != null) {
            transitionNum = transitionIndex.indexOf(gold.transition);
            // TODO: this can theoretically result in a -1 gold
            // transition if the transition exists, but is a
            // CompoundUnaryTransition which only exists because the
            // parser is wrong.  Do we want to add those transitions?
          }
          updates.add(new TrainingUpdate(features, transitionNum, predictedNum, learningRate));
        }
        state = predicted.apply(state);
      }
    } else if (op.trainOptions().trainingMethod == ShiftReduceTrainOptions.TrainingMethod.BEAM ||
               op.trainOptions().trainingMethod == ShiftReduceTrainOptions.TrainingMethod.REORDER_BEAM) {
      if (op.trainOptions().beamSize <= 0) {
        throw new IllegalArgumentException("Illegal beam size " + op.trainOptions().beamSize);
      }
      PriorityQueue<State> agenda = new PriorityQueue<>(op.trainOptions().beamSize + 1, ScoredComparator.ASCENDING_COMPARATOR);
      State goldState = example.initialStateFromGoldTagTree();
      List<Transition> transitions = example.trainTransitions();
      agenda.add(goldState);
      // int transitionCount = 0;
      while (transitions.size() > 0) {
        Transition goldTransition = transitions.get(0);
        Transition highestScoringTransitionFromGoldState = null;
        double highestScoreFromGoldState = 0.0;
        PriorityQueue<State> newAgenda = new PriorityQueue<>(op.trainOptions().beamSize + 1, ScoredComparator.ASCENDING_COMPARATOR);
        State highestScoringState = null;
        State highestCurrentState = null;
        for (State currentState : agenda) {
          boolean isGoldState = (op.trainOptions().trainingMethod == ShiftReduceTrainOptions.TrainingMethod.REORDER_BEAM &&
                                 goldState.areTransitionsEqual(currentState));

          List<String> features = featureFactory.featurize(currentState);
          Collection<ScoredObject<Integer>> stateTransitions = findHighestScoringTransitions(currentState, features, true, op.trainOptions().beamSize, null);
          for (ScoredObject<Integer> transition : stateTransitions) {
            State newState = transitionIndex.get(transition.object()).apply(currentState, transition.score());
            newAgenda.add(newState);
            if (newAgenda.size() > op.trainOptions().beamSize) {
              newAgenda.poll();
            }
            if (highestScoringState == null || highestScoringState.score() < newState.score()) {
              highestScoringState = newState;
              highestCurrentState = currentState;
            }
            if (isGoldState &&
                (highestScoringTransitionFromGoldState == null || transition.score() > highestScoreFromGoldState)) {
              highestScoringTransitionFromGoldState = transitionIndex.get(transition.object());
              highestScoreFromGoldState = transition.score();
            }
          }
        }

        // This can happen if the REORDER_BEAM method backs itself
        // into a corner, such as transitioning to something that
        // can't have a FinalizeTransition applied.  This doesn't
        // happen for the BEAM method because in that case the correct
        // state (eg one with ROOT) isn't on the agenda so it stops.
        if (op.trainOptions().trainingMethod == ShiftReduceTrainOptions.TrainingMethod.REORDER_BEAM &&
            highestScoringTransitionFromGoldState == null) {
          break;
        }

        if (highestScoringState == null) {
          System.err.println("Unable to find a best transition!");
          System.err.println("Previous agenda:");
          for (State state : agenda) {
            System.err.println(state);
          }
          System.err.println("Gold transitions:");
          System.err.println(example.transitions);
          break;
        }

        State newGoldState = goldTransition.apply(goldState, 0.0);

        // if highest scoring state used the correct transition, no training
        // otherwise, down the last transition, up the correct
        if (!newGoldState.areTransitionsEqual(highestScoringState)) {
          ++numWrong;
          List<String> goldFeatures = featureFactory.featurize(goldState);
          int lastTransition = transitionIndex.indexOf(highestScoringState.transitions.peek());
          updates.add(new TrainingUpdate(featureFactory.featurize(highestCurrentState), -1, lastTransition, learningRate));
          updates.add(new TrainingUpdate(goldFeatures, transitionIndex.indexOf(goldTransition), -1, learningRate));

          if (op.trainOptions().trainingMethod == ShiftReduceTrainOptions.TrainingMethod.BEAM) {
            // If the correct state has fallen off the agenda, break
            if (!ShiftReduceUtils.findStateOnAgenda(newAgenda, newGoldState)) {
              break;
            } else {
              transitions.remove(0);
            }
          } else if (op.trainOptions().trainingMethod == ShiftReduceTrainOptions.TrainingMethod.REORDER_BEAM) {
            if (!ShiftReduceUtils.findStateOnAgenda(newAgenda, newGoldState)) {
              if (!reorderer.reorder(goldState, highestScoringTransitionFromGoldState, transitions)) {
                break;
              }
              newGoldState = highestScoringTransitionFromGoldState.apply(goldState);
              if (!ShiftReduceUtils.findStateOnAgenda(newAgenda, newGoldState)) {
                break;
              }
            } else {
              transitions.remove(0);
            }
          }
        } else {
          ++numCorrect;
          transitions.remove(0);
        }

        goldState = newGoldState;
        agenda = newAgenda;
      }
    } else if (op.trainOptions().trainingMethod == ShiftReduceTrainOptions.TrainingMethod.REORDER_ORACLE ||
               op.trainOptions().trainingMethod == ShiftReduceTrainOptions.TrainingMethod.EARLY_TERMINATION ||
               op.trainOptions().trainingMethod == ShiftReduceTrainOptions.TrainingMethod.GOLD) {
      State state = example.initialStateFromGoldTagTree();
      List<Transition> transitions = example.trainTransitions();

      boolean keepGoing = true;
      while (transitions.size() > 0 && keepGoing) {
        Transition transition = transitions.get(0);
        int transitionNum = transitionIndex.indexOf(transition);
        List<String> features = featureFactory.featurize(state);
        int predictedNum = findHighestScoringTransition(state, features, false).object();
        Transition predicted = transitionIndex.get(predictedNum);
        if (transitionNum == predictedNum) {
          transitions.remove(0);
          state = transition.apply(state);
          numCorrect++;
        } else {
          numWrong++;
          if (firstError == null) {
            firstError = new Pair<>(predictedNum, transitionNum);
          }
          // TODO: allow weighted features, weighted training, etc
          updates.add(new TrainingUpdate(features, transitionNum, predictedNum, learningRate));
          switch (op.trainOptions().trainingMethod) {
          case EARLY_TERMINATION:
            keepGoing = false;
            break;
          case GOLD:
            transitions.remove(0);
            state = transition.apply(state);
            break;
          case REORDER_ORACLE:
            keepGoing = reorderer.reorder(state, predicted, transitions);
            if (keepGoing) {
              state = predicted.apply(state);
            }
            break;
          default:
            throw new IllegalArgumentException("Unexpected method " + op.trainOptions().trainingMethod);
          }
        }
      }
    }

    return new Quadruple<>(updates, numCorrect, numWrong, firstError);
  }

  private class TrainTreeProcessor implements ThreadsafeProcessor<TrainingExample, Quadruple<List<TrainingUpdate>, Integer, Integer, Pair<Integer, Integer>>> {
    Oracle oracle;

    public TrainTreeProcessor(Oracle oracle) {
      this.oracle = oracle;
    }

    @Override
    public Quadruple<List<TrainingUpdate>, Integer, Integer, Pair<Integer, Integer>> process(TrainingExample example) {
      return trainTree(example, oracle);
    }

    @Override
    public TrainTreeProcessor newInstance() {
      // already threadsafe
      return this;
    }
  }

  /**
   * Trains a batch of trees and returns the following: a list of
   * Update objects, the number of transitions correct, and the number
   * of transitions wrong.
   *
   * If the model is trained with multiple threads, it is expected
   * that a valid MulticoreWrapper is passed in which does the
   * processing.  In that case, the processing is done on all of the
   * trees without updating any weights, which allows the results for
   * multithreaded training to be reproduced.
   */
  private Quadruple<List<TrainingUpdate>, Integer, Integer, List<Pair<Integer, Integer>>> trainBatch(List<TrainingExample> trainingData, Oracle oracle, MulticoreWrapper<TrainingExample, Quadruple<List<TrainingUpdate>, Integer, Integer, Pair<Integer, Integer>>> wrapper) {
    int numCorrect = 0;
    int numWrong = 0;
    final List<TrainingUpdate> updates = Generics.newArrayList();
    final List<Pair<Integer, Integer>> firstErrors = Generics.newArrayList();
    if (op.trainOptions.trainingThreads == 1) {
      for (TrainingExample example : trainingData) {
        Quadruple<List<TrainingUpdate>, Integer, Integer, Pair<Integer, Integer>> update = trainTree(example, oracle);
        updates.addAll(update.first);
        numCorrect += update.second;
        numWrong += update.third;
        if (update.fourth != null) {
          firstErrors.add(update.fourth);
        }
      }
    } else {
      for (TrainingExample example : trainingData) {
        wrapper.put(example);
      }
      wrapper.join(false);
      while (wrapper.peek()) {
        Quadruple<List<TrainingUpdate>, Integer, Integer, Pair<Integer, Integer>> result = wrapper.poll();
        updates.addAll(result.first);
        numCorrect += result.second;
        numWrong += result.third;
        if (result.fourth != null) {
          firstErrors.add(result.fourth);
        }
      }
    }
    return new Quadruple<>(updates, numCorrect, numWrong, firstErrors);
  }


  private double evaluate(Tagger tagger, Treebank devTreebank, String message) {
    ShiftReduceParser temp = new ShiftReduceParser(op, this);
    EvaluateTreebank evaluator = new EvaluateTreebank(temp.getOp(), null, temp, tagger);
    evaluator.testOnTreebank(devTreebank);
    double labelF1 = evaluator.getLBScore();
    log.info(message + ": " + labelF1);
    return labelF1;
  }


  /**
   * This increases f1 slightly, probably by letting the parser know
   * what to do in situations it doesn't get to during the training.
   * <br>
   * TODO: make constants out of 10, 0.5, etc
   */
  void augmentData(List<TrainingExample> augmentedData, List<TrainingExample> trainingData, Random random) {
    for (TrainingExample example : trainingData) {
      if (example.transitions.size() > 10 && random.nextDouble() < 0.5) {
        int pivot = random.nextInt(example.transitions.size() - 10) + 7;
        augmentedData.add(new TrainingExample(example.binarizedTree, example.transitions, pivot));
      }
    }
  }

  private void trainModel(String serializedPath, Tagger tagger, Random random, List<TrainingExample> trainingData, Treebank devTreebank, int nThreads, Set<String> allowedFeatures) {
    double bestScore = 0.0;
    int bestIteration = 0;
    PriorityQueue<ScoredObject<PerceptronModel>> bestModels = null;
    if (op.trainOptions().averagedModels > 0) {
      bestModels = new PriorityQueue<>(op.trainOptions().averagedModels + 1, ScoredComparator.ASCENDING_COMPARATOR);
    }

    Oracle oracle = null;
    if (op.trainOptions().trainingMethod == ShiftReduceTrainOptions.TrainingMethod.ORACLE) {
      oracle = new Oracle(trainingData, op.compoundUnaries, rootStates, rootOnlyStates);
    }

    MulticoreWrapper<TrainingExample, Quadruple<List<TrainingUpdate>, Integer, Integer, Pair<Integer, Integer>>> wrapper = null;
    if (nThreads != 1) {
      wrapper = new MulticoreWrapper<>(op.trainOptions.trainingThreads, new TrainTreeProcessor(oracle));
    }

    IntCounter<String> featureFrequencies = null;
    if (op.trainOptions().featureFrequencyCutoff > 1 && allowedFeatures == null) {
      // allowedFeatures != null means we already filtered rare
      // features once.  Sometimes the exact features found are
      // different depending on how the learning proceeds..  The
      // second time around, we will allow rare features to exist
      featureFrequencies = new IntCounter<>();
    }

    for (int iteration = 1; iteration <= op.trainOptions.trainingIterations; ++iteration) {
      Timing trainingTimer = new Timing();
      int numCorrect = 0;
      int numWrong = 0;
      IntCounter<Pair<Integer, Integer>> firstErrors = new IntCounter<>();

      List<TrainingExample> augmentedData = new ArrayList<TrainingExample>(trainingData);
      augmentData(augmentedData, trainingData, random);
      Collections.shuffle(augmentedData, random);
      log.info("Original list " + trainingData.size() + "; augmented " + augmentedData.size());
      for (int start = 0; start < augmentedData.size(); start += op.trainOptions.batchSize) {
        int end = Math.min(start + op.trainOptions.batchSize, augmentedData.size());
        Quadruple<List<TrainingUpdate>, Integer, Integer, List<Pair<Integer, Integer>>> result = trainBatch(augmentedData.subList(start, end), oracle, wrapper);

        numCorrect += result.second;
        numWrong += result.third;
        for (Pair<Integer, Integer> firstError : result.fourth) {
          firstErrors.incrementCount(firstError);
        }
        if (numWrong < result.fourth.size()) {
          log.error("WTF " + numWrong + " " + result.fourth);
        }

        for (TrainingUpdate update : result.first) {
          for (String feature : update.features) {
            if (allowedFeatures != null && !allowedFeatures.contains(feature)) {
              continue;
            }
            Weight weights = featureWeights.get(feature);
            if (weights == null) {
              weights = new Weight();
              featureWeights.put(feature, weights);
            }
            weights.updateWeight(update.goldTransition, update.delta);
            weights.updateWeight(update.predictedTransition, -update.delta);

            if (featureFrequencies != null) {
              featureFrequencies.incrementCount(feature, (update.goldTransition >= 0 && update.predictedTransition >= 0) ? 2 : 1);
            }
          }
        }
      }

      float l2Reg = op.trainOptions().l2Reg;
      if (l2Reg > 0.0f) {
        for (Map.Entry<String, Weight> weight : featureWeights.entrySet()) {
          weight.getValue().l2Reg(l2Reg);
        }
      }

      float l1Reg = op.trainOptions().l1Reg;
      if (l1Reg > 0.0f) {
        for (Map.Entry<String, Weight> weight : featureWeights.entrySet()) {
          weight.getValue().l1Reg(l1Reg);
        }
      }

      trainingTimer.done("Iteration " + iteration);
      log.info("While training, got " + numCorrect + " transitions correct and " + numWrong + " transitions wrong");
      outputStats();
      if (firstErrors.size() > 0) {
        log.info("Most common transition errors:");
        for (int i = 0; i < 9 && firstErrors.size() > 0; ++i) {
          Pair<Integer, Integer> mostCommon = firstErrors.argmax();
          int count = firstErrors.max();
          firstErrors.decrementCount(mostCommon, count);
          Transition predicted = transitionIndex.get(mostCommon.first());
          Transition gold = transitionIndex.get(mostCommon.second());
          log.info("  # " + (i+1) + ": " + gold + " -> " + predicted + " happened " + firstErrors.max() + " times");
        }
      }

      double labelF1 = 0.0;
      if (devTreebank != null) {
        labelF1 = evaluate(tagger, devTreebank, "Label F1 for iteration " + iteration);

        if (labelF1 > bestScore) {
          log.info("New best dev score (previous best " + bestScore + ")");
          bestScore = labelF1;
          bestIteration = iteration;
        } else {
          log.info("Failed to improve for " + (iteration - bestIteration) + " iteration(s) on previous best score of " + bestScore);
          if (op.trainOptions.stalledIterationLimit > 0 && (iteration - bestIteration >= op.trainOptions.stalledIterationLimit)) {
            log.info("Failed to improve for too long, stopping training");
            break;
          }
        }
        log.info("\n\n");

        if (bestModels != null) {
          bestModels.add(new ScoredObject<>(new PerceptronModel(this), labelF1));
          if (bestModels.size() > op.trainOptions().averagedModels) {
            bestModels.poll();
          }
        }
      }
      if (op.trainOptions().saveIntermediateModels && serializedPath != null && op.trainOptions.debugOutputFrequency > 0) {
        String tempName = serializedPath.substring(0, serializedPath.length() - 7) + "-" + FILENAME.format(iteration) + "-" + NF.format(labelF1) + ".ser.gz";
        ShiftReduceParser temp = new ShiftReduceParser(op, this);
        temp.saveModel(tempName);
        // TODO: we could save a cutoff version of the model,
        // especially if we also get a dev set number for it, but that
        // might be overkill
      }

      if (iteration % 10 == 0 && op.trainOptions().decayLearningRate > 0.0) {
        learningRate *= op.trainOptions().decayLearningRate;
      }
    } // end for iterations

    if (wrapper != null) {
      wrapper.join();
    }

    if (bestModels != null) {
      if (op.trainOptions().cvAveragedModels && devTreebank != null) {
        List<ScoredObject<PerceptronModel>> models = Generics.newArrayList();
        while (bestModels.size() > 0) {
          models.add(bestModels.poll());
        }
        Collections.reverse(models);
        double bestF1 = 0.0;
        int bestSize = 0;
        for (int i = 1; i <= models.size(); ++i) {
          log.info("Testing with " + i + " models averaged together");
          // TODO: this is kind of ugly, would prefer a separate object
          averageScoredModels(models.subList(0, i));
          double labelF1 = evaluate(tagger, devTreebank, "Label F1 for " + i + " models");
          if (labelF1 > bestF1) {
            bestF1 = labelF1;
            bestSize = i;
          }
        }
        averageScoredModels(models.subList(0, bestSize));
        log.info("Label F1 for " + bestSize + " models: " + bestF1);
      } else {
        averageScoredModels(bestModels);
      }
    }

    // TODO: perhaps we should filter the features and then get dev
    // set scores.  That way we can merge the models which are best
    // after filtering.
    if (featureFrequencies != null) {
      filterFeatures(featureFrequencies.keysAbove(op.trainOptions().featureFrequencyCutoff));
    }

    condenseFeatures();
  }

  Set<String> pruneFeatures(Set<String> features, Random random, double drop) {
    Set<String> prunedFeatures = new HashSet<>();
    for (String feature : features) {
      if (random.nextDouble() > drop) {
        prunedFeatures.add(feature);
      }
    }
    if (prunedFeatures.size() == 0) {
      for (String feature : features) {
        prunedFeatures.add(feature);
        break;
      }
    }
    return prunedFeatures;
  }

  /**
   * Will train the model on the given treebank, using devTreebank as
   * a dev set.  If op.retrainAfterCutoff is set, will rerun training
   * after the first time through on a limited set of features.
   */
  @Override
  public void trainModel(String serializedPath, Tagger tagger, Random random, List<TrainingExample> trainingData, Treebank devTreebank, int nThreads) {
    if (op.trainOptions().retrainAfterCutoff && op.trainOptions().featureFrequencyCutoff > 0 ||
        op.trainOptions().retrainShards > 1) {
      String tempName = serializedPath.substring(0, serializedPath.length() - 7) + "-" + "temp.ser.gz";
      trainModel(tempName, tagger, random, trainingData, devTreebank, nThreads, null);
      ShiftReduceParser temp = new ShiftReduceParser(op, this);
      temp.saveModel(tempName);
      log.info("Beginning retraining");
      Set<String> features = featureWeights.keySet();
      featureWeights = Generics.newHashMap();
      trainModel(serializedPath, tagger, random, trainingData, devTreebank, nThreads, features);

      // If we only had one train shard, we are now done.  Otherwise,
      // we retrain N-1 more times, each time dropping a fraction of
      // the features.
      if (op.trainOptions().retrainShards > 1) {
        List<PerceptronModel> shards = Generics.newArrayList();
        shards.add(new PerceptronModel(this));

        for (int i = 1; i < op.trainOptions().retrainShards; ++i) {
          log.info("Beginning retraining of shard " + (i+1));
          Set<String> prunedFeatures = pruneFeatures(features, random, op.trainOptions().retrainShardFeatureDrop);
          featureWeights = Generics.newHashMap();
          trainModel(serializedPath, tagger, random, trainingData, devTreebank, nThreads, prunedFeatures);
          shards.add(new PerceptronModel(this));
        }
        log.info("Averaging " + op.trainOptions().retrainShards + " shards");
        averageModels(shards);
        condenseFeatures();
        evaluate(tagger, devTreebank, "Label F1 for " + op.trainOptions().retrainShards + " averaged shards");
      }
    } else {
      trainModel(serializedPath, tagger, random, trainingData, devTreebank, nThreads, null);
    }
  }

  private static final long serialVersionUID = 1;

}
